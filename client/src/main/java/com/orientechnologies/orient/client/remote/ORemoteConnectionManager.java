/*
  *
  *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
  *  *
  *  *  Licensed under the Apache License, Version 2.0 (the "License");
  *  *  you may not use this file except in compliance with the License.
  *  *  You may obtain a copy of the License at
  *  *
  *  *       http://www.apache.org/licenses/LICENSE-2.0
  *  *
  *  *  Unless required by applicable law or agreed to in writing, software
  *  *  distributed under the License is distributed on an "AS IS" BASIS,
  *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *  *  See the License for the specific language governing permissions and
  *  *  limitations under the License.
  *  *
  *  * For more information: http://www.orientechnologies.com
  *
  */
package com.orientechnologies.orient.client.remote;

import com.orientechnologies.common.concur.resource.OResourcePool;
import com.orientechnologies.common.concur.resource.OResourcePoolListener;
import com.orientechnologies.common.io.OIOException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.config.OContextConfiguration;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.enterprise.channel.OChannel;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryAsynchClient;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryProtocol;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelListener;
import com.orientechnologies.orient.enterprise.channel.binary.ORemoteServerEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages network connections against OrientDB servers. All the connection pools are managed in a Map<url,pool>, but in the future
 * we could have a unique pool per sever and manage database connections over the protocol.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 */
public class ORemoteConnectionManager implements OChannelListener {
  public static final String                                                                   PARAM_MAX_POOL = "maxpool";

  protected final ConcurrentHashMap<String, OResourcePool<String, OChannelBinaryAsynchClient>> connections;
  protected final long                                                                         timeout;

  public ORemoteConnectionManager(final int iMaxConnectionPerURL, final long iTimeout) {
    connections = new ConcurrentHashMap<String, OResourcePool<String, OChannelBinaryAsynchClient>>();
    timeout = iTimeout;
  }

  public void close() {
    for (Map.Entry<String, OResourcePool<String, OChannelBinaryAsynchClient>> entry : connections.entrySet())
      entry.getValue().close();

    connections.clear();
  }

  public OChannelBinaryAsynchClient acquire(String iServerURL, final OContextConfiguration clientConfiguration,
      final Map<String, Object> iConfiguration, final ORemoteServerEventListener iListener) {
    OResourcePool<String, OChannelBinaryAsynchClient> pool = connections.get(iServerURL);
    if (pool == null) {
      int maxPool = OGlobalConfiguration.CLIENT_CHANNEL_MAX_POOL.getValueAsInteger();

      if (iConfiguration != null && iConfiguration.size() > 0) {
        if (iConfiguration.containsKey(PARAM_MAX_POOL))
          maxPool = Integer.parseInt(iConfiguration.get(PARAM_MAX_POOL).toString());
      }

      pool = new OResourcePool<String, OChannelBinaryAsynchClient>(maxPool,
          new OResourcePoolListener<String, OChannelBinaryAsynchClient>() {
            @Override
            public OChannelBinaryAsynchClient createNewResource(final String iKey, final Object... iAdditionalArgs) {
              return createNetworkConnection(iKey, (OContextConfiguration) iAdditionalArgs[0],
                  (Map<String, Object>) iAdditionalArgs[1], (ORemoteServerEventListener) iAdditionalArgs[2]);
            }

            @Override
            public boolean reuseResource(final String iKey, final Object[] iAdditionalArgs, final OChannelBinaryAsynchClient iValue) {
              return true;
            }
          });

      final OResourcePool<String, OChannelBinaryAsynchClient> prev = connections.putIfAbsent(iServerURL, pool);
      if (prev != null) {
        // ALREADY PRESENT, DESTROY IT AND GET THE ALREADY EXISTENT OBJ
        pool.close();
        pool = prev;
      }
    }

    try {
      // RETURN THE RESOURCE
      return pool.getResource(iServerURL, timeout, clientConfiguration, iConfiguration, iListener);

    } catch (RuntimeException e) {
      // ERROR ON RETRIEVING THE INSTANCE FROM THE POOL
      connections.remove(iServerURL);
      throw e;
    } catch (Exception e) {
      // ERROR ON RETRIEVING THE INSTANCE FROM THE POOL
      OLogManager.instance().error(this, "Error on retrieving the connection from pool: " + iServerURL, e);
      connections.remove(iServerURL);
    }
    return null;
  }

  public void release(final OChannelBinaryAsynchClient conn) {
    final OResourcePool<String, OChannelBinaryAsynchClient> pool = connections.get(conn.getServerURL());
    if (pool != null) {
      if (!conn.isConnected()) {
        OLogManager.instance().debug(this, "Network connection pool is receiving a closed connection to reuse: discard it");
        pool.remove(conn);
      } else
        pool.returnResource(conn);
    }
  }

  public void remove(final OChannelBinaryAsynchClient conn) {
    if (conn.isConnected()) {
      try {
        conn.unlock();
      } catch (Exception e) {
      }

      try {
        conn.close();
      } catch (Exception e) {
      }
    }

    final OResourcePool<String, OChannelBinaryAsynchClient> pool = connections.get(conn.getServerURL());
    if (pool == null)
      throw new IllegalStateException("Connection cannot be released because the pool doesn't exist anymore");

    pool.remove(conn);
  }

  @Override
  public void onChannelClose(final OChannel channel) {
    remove((OChannelBinaryAsynchClient) channel);
  }

  public Set<String> getURLs() {
    return connections.keySet();
  }

  public int getMaxResources(final String url) {
    final OResourcePool<String, OChannelBinaryAsynchClient> pool = connections.get(url);
    if (pool == null)
      return 0;

    return pool.getMaxResources();
  }

  public int getAvailableConnections(final String url) {
    final OResourcePool<String, OChannelBinaryAsynchClient> pool = connections.get(url);
    if (pool == null)
      return 0;

    return pool.getAvailableResources();
  }

  public int getCreatedInstancesInPool(final String url) {
    final OResourcePool<String, OChannelBinaryAsynchClient> pool = connections.get(url);
    if (pool == null)
      return 0;

    return pool.getCreatedInstancesInPool();
  }

  public void closePool(final String url) {
    final OResourcePool<String, OChannelBinaryAsynchClient> pool = connections.remove(url);
    if (pool == null)
      return;

    closePool(pool);
  }

  protected void closePool(final OResourcePool<String, OChannelBinaryAsynchClient> pool) {
    final List<OChannelBinaryAsynchClient> conns = new ArrayList<OChannelBinaryAsynchClient>(pool.getResources());
    for (OChannelBinaryAsynchClient c : conns)
      try {
        c.close();
      } catch (Exception e) {
      }
  }

  protected OChannelBinaryAsynchClient createNetworkConnection(String iServerURL, final OContextConfiguration clientConfiguration,
      Map<String, Object> iAdditionalArg, final ORemoteServerEventListener asynchEventListener) throws OIOException {
    if (iServerURL == null)
      throw new IllegalArgumentException("server url is null");

    // TRY WITH CURRENT URL IF ANY
    try {
      OLogManager.instance().debug(this, "Trying to connect to the remote host %s...", iServerURL);

      final String serverURL;
      final String databaseName;
      int sepPos = iServerURL.indexOf("/");
      if (sepPos > -1) {
        // REMOVE DATABASE NAME IF ANY
        serverURL = iServerURL.substring(0, sepPos);
        databaseName = iServerURL.substring(sepPos + 1);
      } else {
        serverURL = iServerURL;
        databaseName = null;
      }

      sepPos = serverURL.indexOf(":");
      final String remoteHost = serverURL.substring(0, sepPos);
      final int remotePort = Integer.parseInt(serverURL.substring(sepPos + 1));

      final OChannelBinaryAsynchClient ch = new OChannelBinaryAsynchClient(remoteHost, remotePort, databaseName,
          clientConfiguration, OChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION, asynchEventListener);

      // REGISTER MYSELF AS LISTENER TO REMOVE THE CHANNEL FROM THE POOL IN CASE OF CLOSING
      ch.registerListener(this);

      return ch;

    } catch (OIOException e) {
      // RE-THROW IT
      throw e;
    } catch (Exception e) {
      OLogManager.instance().debug(this, "Error on connecting to %s", e, iServerURL);
      throw new OIOException("Error on connecting to " + iServerURL, e);
    }
  }
}
