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
package com.orientechnologies.orient.core.db.record;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.cache.OCacheLevelOneLocatorImpl;
import com.orientechnologies.orient.core.cache.OLocalRecordCache;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.command.OCommandRequestInternal;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.conflict.ORecordConflictStrategy;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseComplex;
import com.orientechnologies.orient.core.db.ODatabaseComplexInternal;
import com.orientechnologies.orient.core.db.ODatabaseLifecycleListener;
import com.orientechnologies.orient.core.db.ODatabaseListener;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.ODatabaseWrapperAbstract;
import com.orientechnologies.orient.core.db.OHookReplacedRecordThreadLocal;
import com.orientechnologies.orient.core.db.OScenarioThreadLocal;
import com.orientechnologies.orient.core.db.OScenarioThreadLocal.RUN_MODE;
import com.orientechnologies.orient.core.db.raw.ODatabaseRaw;
import com.orientechnologies.orient.core.db.record.ridbag.sbtree.ORidBagDeleteHook;
import com.orientechnologies.orient.core.db.record.ridbag.sbtree.OSBTreeCollectionManager;
import com.orientechnologies.orient.core.db.record.ridbag.sbtree.OSBTreeCollectionManagerProxy;
import com.orientechnologies.orient.core.dictionary.ODictionary;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.exception.OSchemaException;
import com.orientechnologies.orient.core.exception.OSecurityAccessException;
import com.orientechnologies.orient.core.fetch.OFetchHelper;
import com.orientechnologies.orient.core.hook.OHookThreadLocal;
import com.orientechnologies.orient.core.hook.ORecordHook;
import com.orientechnologies.orient.core.hook.ORecordHook.DISTRIBUTED_EXECUTION_MODE;
import com.orientechnologies.orient.core.hook.ORecordHook.RESULT;
import com.orientechnologies.orient.core.hook.ORecordHook.TYPE;
import com.orientechnologies.orient.core.id.OClusterPosition;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.index.OClassIndexManager;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.index.OIndexManager;
import com.orientechnologies.orient.core.iterator.ORecordIteratorCluster;
import com.orientechnologies.orient.core.metadata.OMetadataDefault;
import com.orientechnologies.orient.core.metadata.function.OFunctionTrigger;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.security.ODatabaseSecurityResources;
import com.orientechnologies.orient.core.metadata.security.ORestrictedAccessHook;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.metadata.security.OUser;
import com.orientechnologies.orient.core.metadata.security.OUserTrigger;
import com.orientechnologies.orient.core.query.OQuery;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.schedule.OSchedulerTrigger;
import com.orientechnologies.orient.core.serialization.serializer.binary.OBinarySerializerFactory;
import com.orientechnologies.orient.core.serialization.serializer.record.ORecordSerializer;
import com.orientechnologies.orient.core.serialization.serializer.record.ORecordSerializerFactory;
import com.orientechnologies.orient.core.serialization.serializer.record.string.ORecordSerializerSchemaAware2CSV;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.ORecordCallback;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.OStorageEmbedded;
import com.orientechnologies.orient.core.storage.OStorageOperationResult;
import com.orientechnologies.orient.core.storage.OStorageProxy;
import com.orientechnologies.orient.core.storage.impl.local.paginated.ORecordSerializationContext;
import com.orientechnologies.orient.core.tx.OTransactionRealAbstract;
import com.orientechnologies.orient.core.type.tree.provider.OMVRBTreeRIDProvider;
import com.orientechnologies.orient.core.version.ORecordVersion;
import com.orientechnologies.orient.core.version.OVersionFactory;

@SuppressWarnings("unchecked")
public abstract class ODatabaseRecordAbstract extends ODatabaseWrapperAbstract<ODatabaseRaw> implements ODatabaseRecordInternal {

  @Deprecated
  private static final String                               DEF_RECORD_FORMAT = "csv";
  private final Map<ORecordHook, ORecordHook.HOOK_POSITION> unmodifiableHooks;
  protected ORecordSerializer                               serializer;
  private OSBTreeCollectionManager                          sbTreeCollectionManager;
  private OMetadataDefault                                  metadata;
  private OUser                                             user;
  private byte                                              recordType;
  @Deprecated
  private String                                            recordFormat;
  private Map<ORecordHook, ORecordHook.HOOK_POSITION>       hooks             = new LinkedHashMap<ORecordHook, ORecordHook.HOOK_POSITION>();
  private boolean                                           retainRecords     = true;
  private OLocalRecordCache                                 level1Cache;
  private boolean                                           mvcc;
  private boolean                                           validation;
  private OCurrentStorageComponentsFactory                  componentsFactory;

  public ODatabaseRecordAbstract(final String iURL, final byte iRecordType) {
    super(new ODatabaseRaw(iURL));
    setCurrentDatabaseinThreadLocal();

    underlying.setOwner(this);

    unmodifiableHooks = Collections.unmodifiableMap(hooks);

    databaseOwner = this;

    recordType = iRecordType;
    level1Cache = new OLocalRecordCache(new OCacheLevelOneLocatorImpl());

    mvcc = OGlobalConfiguration.DB_MVCC.getValueAsBoolean();
    validation = OGlobalConfiguration.DB_VALIDATION.getValueAsBoolean();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <DB extends ODatabase> DB open(final String iUserName, final String iUserPassword) {
    setCurrentDatabaseinThreadLocal();

    try {
      super.open(iUserName, iUserPassword);
      ORecordSerializerFactory serializerFactory = ORecordSerializerFactory.instance();
      String serializeName = getStorage().getConfiguration().getRecordSerializer();
      if (serializeName == null)
        serializeName = ORecordSerializerSchemaAware2CSV.NAME;
      serializer = serializerFactory.getFormat(serializeName);
      if (serializer == null)
        throw new ODatabaseException("RecordSerializer with name '" + serializeName + "' not found ");
      if (getStorage().getConfiguration().getRecordSerializerVersion() > serializer.getMinSupportedVersion())
        // TODO: I need a better message!
        throw new ODatabaseException("Persistent record serializer version is not support by the current implementation");

      componentsFactory = getStorage().getComponentsFactory();

      final OSBTreeCollectionManager sbTreeCM = getStorage().getResource(OSBTreeCollectionManager.class.getSimpleName(),
          new Callable<OSBTreeCollectionManager>() {
            @Override
            public OSBTreeCollectionManager call() throws Exception {
              Class<? extends OSBTreeCollectionManager> managerClass = getStorage().getCollectionManagerClass();

              if (managerClass == null) {
                OLogManager.instance().warn(this, "Current implementation of storage does not support sbtree collections");
                return null;
              } else {
                return managerClass.newInstance();
              }
            }
          });

      sbTreeCollectionManager = sbTreeCM != null ? new OSBTreeCollectionManagerProxy(this, sbTreeCM) : null;

      level1Cache.startup();

      metadata = new OMetadataDefault();
      metadata.load();

      recordFormat = DEF_RECORD_FORMAT;

      if (!(getStorage() instanceof OStorageProxy)) {
        if (metadata.getIndexManager().autoRecreateIndexesAfterCrash()) {
          metadata.getIndexManager().recreateIndexes();

          setCurrentDatabaseinThreadLocal();
          user = null;
        }

        installHooks();

        user = metadata.getSecurity().authenticate(iUserName, iUserPassword);
        if (user != null) {
          final Set<ORole> roles = user.getRoles();
          if (roles == null || roles.isEmpty() || roles.iterator().next() == null) {
            // SEEMS CORRUPTED: INSTALL DEFAULT ROLE
            for (ODatabaseListener l : underlying.browseListeners()) {
              if (l.onCorruptionRepairDatabase(this, "Security metadata is broken: current user '" + user.getName()
                  + "' has no roles defined",
                  "The 'admin' user will be reinstalled with default role ('admin') and password 'admin'")) {
                user = null;
                user = metadata.getSecurity().repair();
                break;
              }
            }
          }
        }
      } else
        // REMOTE CREATE DUMMY USER
        user = new OUser(iUserName, OUser.encryptPassword(iUserPassword)).addRole(new ORole("passthrough", null,
            ORole.ALLOW_MODES.ALLOW_ALL_BUT));

      checkSecurity(ODatabaseSecurityResources.DATABASE, ORole.PERMISSION_READ);

      if (!metadata.getSchema().existsClass(OMVRBTreeRIDProvider.PERSISTENT_CLASS_NAME))
        // @COMPATIBILITY 1.0RC9
        metadata.getSchema().createClass(OMVRBTreeRIDProvider.PERSISTENT_CLASS_NAME);

      // WAKE UP LISTENERS
      callOnOpenListeners();

    } catch (OException e) {
      close();
      throw e;
    } catch (Exception e) {
      close();
      throw new ODatabaseException("Cannot open database", e);
    }
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <DB extends ODatabase> DB create() {
    return create(null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <DB extends ODatabase> DB create(final Map<OGlobalConfiguration, Object> iInitialSettings) {
    setCurrentDatabaseinThreadLocal();

    try {
      super.create(iInitialSettings);
      componentsFactory = getStorage().getComponentsFactory();

      sbTreeCollectionManager = new OSBTreeCollectionManagerProxy(this, getStorage().getResource(
          OSBTreeCollectionManager.class.getSimpleName(), new Callable<OSBTreeCollectionManager>() {
            @Override
            public OSBTreeCollectionManager call() throws Exception {
              Class<? extends OSBTreeCollectionManager> managerClass = getStorage().getCollectionManagerClass();

              if (managerClass == null) {
                OLogManager.instance().warn(this, "Current implementation of storage does not support sbtree collections");
                return null;
              } else {
                return managerClass.newInstance();
              }
            }
          }));
      level1Cache.startup();

      getStorage().getConfiguration().setRecordSerializer(getSerializer().toString());
      getStorage().getConfiguration().setRecordSerializerVersion(getSerializer().getCurrentVersion());

      getStorage().getConfiguration().update();

      if (!(getStorage() instanceof OStorageProxy))
        installHooks();

      // CREATE THE DEFAULT SCHEMA WITH DEFAULT USER
      metadata = new OMetadataDefault();
      metadata.create();

      user = getMetadata().getSecurity().getUser(OUser.ADMIN);

      if (!metadata.getSchema().existsClass(OMVRBTreeRIDProvider.PERSISTENT_CLASS_NAME))
        // @COMPATIBILITY 1.0RC9
        metadata.getSchema().createClass(OMVRBTreeRIDProvider.PERSISTENT_CLASS_NAME);

      // WAKE UP DB LIFECYCLE LISTENER
      for (Iterator<ODatabaseLifecycleListener> it = Orient.instance().getDbLifecycleListeners(); it.hasNext();)
        it.next().onCreate(getDatabaseOwner());

      // WAKE UP LISTENERS
      for (ODatabaseListener listener : underlying.browseListeners())
        try {
          listener.onCreate(underlying);
        } catch (Throwable ignore) {
        }

    } catch (Exception e) {
      throw new ODatabaseException("Cannot create database", e);
    }
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public OBinarySerializerFactory getSerializerFactory() {
    return componentsFactory.binarySerializerFactory;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public OCurrentStorageComponentsFactory getStorageVersions() {
    return componentsFactory;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drop() {
    checkOpeness();
    checkSecurity(ODatabaseSecurityResources.DATABASE, ORole.PERMISSION_DELETE);

    setCurrentDatabaseinThreadLocal();

    callOnCloseListeners();

    if (metadata != null) {
      metadata.close();
      metadata = null;
    }

    super.drop();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    setCurrentDatabaseinThreadLocal();

    callOnCloseListeners();

    for (ORecordHook h : hooks.keySet())
      h.onUnregister();
    hooks.clear();

    if (metadata != null) {
      if (!(getStorage() instanceof OStorageProxy)) {
        final OIndexManager indexManager = metadata.getIndexManager();

        if (indexManager != null)
          indexManager.waitTillIndexRestore();
      }

      if (metadata != null) {
        metadata.close();
        metadata = null;
      }
    }

    super.close();

    user = null;
    level1Cache.shutdown();
    ODatabaseRecordThreadLocal.INSTANCE.remove();
  }

  /**
   * {@inheritDoc}
   */
  public ODictionary<ORecord> getDictionary() {
    checkOpeness();
    return metadata.getIndexManager().getDictionary();
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends ORecord> RET getRecord(final OIdentifiable iIdentifiable) {
    if (iIdentifiable instanceof ORecord)
      return (RET) iIdentifiable;
    return (RET) load(iIdentifiable.getIdentity());
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends ORecord> RET load(final ORecord iRecord) {
    return (RET) load(iRecord, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void reload() {
    metadata.reload();
    super.reload();
  }

  public <RET extends ORecord> RET reload(final ORecord iRecord) {
    return executeReadRecord((ORecordId) iRecord.getIdentity(), iRecord, null, true, false, OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  public <RET extends ORecord> RET reload(final ORecord iRecord, final String iFetchPlan) {
    return (RET) executeReadRecord((ORecordId) iRecord.getIdentity(), iRecord, iFetchPlan, true, false,
        OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  public <RET extends ORecord> RET reload(final ORecord iRecord, final String iFetchPlan, boolean iIgnoreCache) {
    return (RET) executeReadRecord((ORecordId) iRecord.getIdentity(), iRecord, iFetchPlan, iIgnoreCache, false,
        OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * Loads a record using a fetch plan.
   */
  public <RET extends ORecord> RET load(final ORecord iRecord, final String iFetchPlan) {
    return (RET) executeReadRecord((ORecordId) iRecord.getIdentity(), iRecord, iFetchPlan, false, false,
        OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends ORecord> RET load(final ORecord iRecord, final String iFetchPlan, final boolean iIgnoreCache) {
    return (RET) executeReadRecord((ORecordId) iRecord.getIdentity(), iRecord, iFetchPlan, iIgnoreCache, false,
        OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <RET extends ORecord> RET load(ORecord iRecord, String iFetchPlan, boolean iIgnoreCache, boolean loadTombstone,
      OStorage.LOCKING_STRATEGY iLockingStrategy) {
    return (RET) executeReadRecord((ORecordId) iRecord.getIdentity(), iRecord, iFetchPlan, iIgnoreCache, loadTombstone,
        iLockingStrategy);
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends ORecord> RET load(final ORID recordId) {
    return (RET) executeReadRecord((ORecordId) recordId, null, null, false, false, OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends ORecord> RET load(final ORID iRecordId, final String iFetchPlan) {
    return (RET) executeReadRecord((ORecordId) iRecordId, null, iFetchPlan, false, false, OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends ORecord> RET load(final ORID iRecordId, final String iFetchPlan, final boolean iIgnoreCache) {
    return (RET) executeReadRecord((ORecordId) iRecordId, null, iFetchPlan, iIgnoreCache, false, OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <RET extends ORecord> RET load(ORID iRecordId, String iFetchPlan, boolean iIgnoreCache, boolean loadTombstone,
      OStorage.LOCKING_STRATEGY iLockingStrategy) {
    return (RET) executeReadRecord((ORecordId) iRecordId, null, iFetchPlan, iIgnoreCache, loadTombstone, iLockingStrategy);
  }

  /**
   * Updates the record without checking the version.
   */
  public <RET extends ORecord> RET save(final ORecord iContent) {
    return (RET) executeSaveRecord(iContent, null, iContent.getRecordVersion(), true, OPERATION_MODE.SYNCHRONOUS, false, null, null);
  }

  /**
   * Updates the record without checking the version.
   * 
   * @param iForceCreate
   *          Flag that indicates that record should be created. If record with current rid already exists, exception is thrown
   * @param iRecordCreatedCallback
   *          call back for record create
   * @param iRecordUpdatedCallback
   *          call back for record update
   */
  public <RET extends ORecord> RET save(final ORecord iContent, final OPERATION_MODE iMode, boolean iForceCreate,
      final ORecordCallback<? extends Number> iRecordCreatedCallback, ORecordCallback<ORecordVersion> iRecordUpdatedCallback) {
    return (RET) executeSaveRecord(iContent, null, iContent.getRecordVersion(), true, iMode, iForceCreate, iRecordCreatedCallback,
        iRecordUpdatedCallback);
  }

  /**
   * Updates the record in the requested cluster without checking the version.
   */
  public <RET extends ORecord> RET save(final ORecord iContent, final String iClusterName) {
    return (RET) executeSaveRecord(iContent, iClusterName, iContent.getRecordVersion(), true, OPERATION_MODE.SYNCHRONOUS, false,
        null, null);
  }

  /**
   * {@inheritDoc}
   */
  public ORecordSerializer getSerializer() {
    return serializer;
  }

  public void setSerializer(ORecordSerializer serializer) {
    this.serializer = serializer;
  }

  /**
   * Updates the record in the requested cluster without checking the version.
   * 
   * @param iForceCreate
   *          Flag that indicates that record should be created. If record with current rid already exists, exception is thrown
   * @param iRecordCreatedCallback
   *          call back for record create
   * @param iRecordUpdatedCallback
   *          call back for record update
   */
  public <RET extends ORecord> RET save(final ORecord iContent, final String iClusterName, final OPERATION_MODE iMode,
      boolean iForceCreate, final ORecordCallback<? extends Number> iRecordCreatedCallback,
      ORecordCallback<ORecordVersion> iRecordUpdatedCallback) {
    return (RET) executeSaveRecord(iContent, iClusterName, iContent.getRecordVersion(), true, iMode, iForceCreate,
        iRecordCreatedCallback, iRecordUpdatedCallback);
  }

  /**
   * Deletes the record without checking the version.
   */
  public ODatabaseComplex<ORecord> delete(final ORID iRecord) {
    executeDeleteRecord(iRecord, OVersionFactory.instance().createUntrackedVersion(), true, true, OPERATION_MODE.SYNCHRONOUS, false);
    return this;
  }

  /**
   * Deletes the record checking the version.
   */
  public ODatabaseComplex<ORecord> delete(final ORID iRecord, final ORecordVersion iVersion) {
    executeDeleteRecord(iRecord, iVersion, true, true, OPERATION_MODE.SYNCHRONOUS, false);
    return this;
  }

  @Override
  public boolean hide(ORID rid) {
    return executeHideRecord(rid, OPERATION_MODE.SYNCHRONOUS);
  }

  public ODatabaseComplex<ORecord> cleanOutRecord(final ORID iRecord, final ORecordVersion iVersion) {
    executeDeleteRecord(iRecord, iVersion, true, true, OPERATION_MODE.SYNCHRONOUS, true);
    return this;
  }

  /**
   * Deletes the record without checking the version.
   */
  public ODatabaseRecord delete(final ORID iRecord, final OPERATION_MODE iMode) {
    executeDeleteRecord(iRecord, OVersionFactory.instance().createUntrackedVersion(), true, true, iMode, false);
    return this;
  }

  /**
   * Deletes the record without checking the version.
   */
  public ODatabaseComplex<ORecord> delete(final ORecord iRecord) {
    executeDeleteRecord(iRecord, OVersionFactory.instance().createUntrackedVersion(), true, true, OPERATION_MODE.SYNCHRONOUS, false);
    return this;
  }

  /**
   * Deletes the record without checking the version.
   */
  public ODatabaseRecord delete(final ORecord iRecord, final OPERATION_MODE iMode) {
    executeDeleteRecord(iRecord, OVersionFactory.instance().createUntrackedVersion(), true, true, iMode, false);
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public <REC extends ORecord> ORecordIteratorCluster<REC> browseCluster(final String iClusterName, final Class<REC> iClass) {
    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, iClusterName);

    setCurrentDatabaseinThreadLocal();

    final int clusterId = getClusterIdByName(iClusterName);

    return new ORecordIteratorCluster<REC>(this, this, clusterId, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <REC extends ORecord> ORecordIteratorCluster<REC> browseCluster(final String iClusterName, final Class<REC> iRecordClass,
      final OClusterPosition startClusterPosition, final OClusterPosition endClusterPosition, final boolean loadTombstones) {
    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, iClusterName);

    setCurrentDatabaseinThreadLocal();

    final int clusterId = getClusterIdByName(iClusterName);

    return new ORecordIteratorCluster<REC>(this, this, clusterId, startClusterPosition, endClusterPosition, true, loadTombstones,
        OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <REC extends ORecord> ORecordIteratorCluster<REC> browseCluster(final String iClusterName,
      final OClusterPosition startClusterPosition, final OClusterPosition endClusterPosition, final boolean loadTombstones) {
    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, iClusterName);

    setCurrentDatabaseinThreadLocal();

    final int clusterId = getClusterIdByName(iClusterName);

    return new ORecordIteratorCluster<REC>(this, this, clusterId, startClusterPosition, endClusterPosition, true, loadTombstones,
        OStorage.LOCKING_STRATEGY.DEFAULT);
  }

  /**
   * {@inheritDoc}
   */
  public ORecordIteratorCluster<?> browseCluster(final String iClusterName) {
    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, iClusterName);

    setCurrentDatabaseinThreadLocal();

    final int clusterId = getClusterIdByName(iClusterName);

    return new ORecordIteratorCluster<ORecord>(this, this, clusterId, true);
  }

  /**
   * {@inheritDoc}
   */
  public OCommandRequest command(final OCommandRequest iCommand) {
    checkSecurity(ODatabaseSecurityResources.COMMAND, ORole.PERMISSION_READ);

    setCurrentDatabaseinThreadLocal();

    final OCommandRequestInternal command = (OCommandRequestInternal) iCommand;

    try {
      command.reset();
      return command;

    } catch (Exception e) {
      throw new ODatabaseException("Error on command execution", e);
    }
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends List<?>> RET query(final OQuery<?> iCommand, final Object... iArgs) {
    setCurrentDatabaseinThreadLocal();

    iCommand.reset();
    return (RET) iCommand.execute(iArgs);
  }

  /**
   * {@inheritDoc}
   */
  public byte getRecordType() {
    return recordType;
  }

  /**
   * {@inheritDoc}
   */
  public <RET> RET newInstance() {
    return (RET) Orient.instance().getRecordFactoryManager().newInstance(recordType);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(final int[] iClusterIds) {
    return countClusterElements(iClusterIds, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(final int iClusterId) {
    return countClusterElements(iClusterId, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(int iClusterId, boolean countTombstones) {
    final String name = getClusterNameById(iClusterId);
    if (name == null)
      return 0;
    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, name);
    setCurrentDatabaseinThreadLocal();

    return super.countClusterElements(iClusterId, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(int[] iClusterIds, boolean countTombstones) {
    String name;
    for (int iClusterId : iClusterIds) {
      name = getClusterNameById(iClusterId);
      checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, name);
    }

    return super.countClusterElements(iClusterIds, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(final String iClusterName) {
    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, iClusterName);
    setCurrentDatabaseinThreadLocal();
    return super.countClusterElements(iClusterName);
  }

  /**
   * {@inheritDoc}
   */
  public OMetadataDefault getMetadata() {
    checkOpeness();
    return metadata;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseRecord> DB checkSecurity(final String iResource, final int iOperation) {
    if (user != null) {
      try {
        user.allow(iResource, iOperation);
      } catch (OSecurityAccessException e) {

        if (OLogManager.instance().isDebugEnabled())
          OLogManager.instance().debug(this,
              "[checkSecurity] User '%s' tried to access to the reserved resource '%s', operation '%s'", getUser(), iResource,
              iOperation);

        throw e;
      }
    }
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseRecord> DB checkSecurity(final String iResourceGeneric, final int iOperation,
      final Object... iResourcesSpecific) {

    if (user != null) {
      try {
        final StringBuilder keyBuffer = new StringBuilder(256);

        boolean ruleFound = false;
        for (Object target : iResourcesSpecific) {
          if (target != null) {
            keyBuffer.setLength(0);
            keyBuffer.append(iResourceGeneric);
            keyBuffer.append('.');
            keyBuffer.append(target.toString());

            final String key = keyBuffer.toString();

            if (user.isRuleDefined(key)) {
              ruleFound = true;
              // RULE DEFINED: CHECK AGAINST IT
              user.allow(key, iOperation);
            }
          }
        }

        if (!ruleFound) {
          // CHECK AGAINST GENERIC RULE
          keyBuffer.setLength(0);
          keyBuffer.append(iResourceGeneric);
          keyBuffer.append('.');
          keyBuffer.append(ODatabaseSecurityResources.ALL);

          user.allow(keyBuffer.toString(), iOperation);
        }

      } catch (OSecurityAccessException e) {
        if (OLogManager.instance().isDebugEnabled())
          OLogManager.instance().debug(this,
              "[checkSecurity] User '%s' tried to access to the reserved resource '%s', target(s) '%s', operation '%s'", getUser(),
              iResourceGeneric, Arrays.toString(iResourcesSpecific), iOperation);

        throw e;
      }
    }
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseRecord> DB checkSecurity(final String iResourceGeneric, final int iOperation,
      final Object iResourceSpecific) {
    checkOpeness();
    if (user != null) {
      try {
        final StringBuilder keyBuffer = new StringBuilder(256);

        boolean ruleFound = false;
        if (iResourceSpecific != null) {
          keyBuffer.setLength(0);
          keyBuffer.append(iResourceGeneric);
          keyBuffer.append('.');
          keyBuffer.append(iResourceSpecific.toString());

          final String key = keyBuffer.toString();

          if (user.isRuleDefined(key)) {
            ruleFound = true;
            // RULE DEFINED: CHECK AGAINST IT
            user.allow(key, iOperation);
          }
        }

        if (!ruleFound) {
          // CHECK AGAINST GENERIC RULE
          keyBuffer.setLength(0);
          keyBuffer.append(iResourceGeneric);
          keyBuffer.append('.');
          keyBuffer.append(ODatabaseSecurityResources.ALL);

          user.allow(keyBuffer.toString(), iOperation);
        }

      } catch (OSecurityAccessException e) {
        if (OLogManager.instance().isDebugEnabled())
          OLogManager.instance().debug(this,
              "[checkSecurity] User '%s' tried to access to the reserved resource '%s', target '%s', operation '%s'", getUser(),
              iResourceGeneric, iResourceSpecific, iOperation);

        throw e;
      }
    }
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends ORecord> RET executeReadRecord(final ORecordId rid, ORecord iRecord, final String iFetchPlan,
      final boolean iIgnoreCache, final boolean loadTombstones, final OStorage.LOCKING_STRATEGY iLockingStrategy) {
    checkOpeness();

    try {
      checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, getClusterNameById(rid.getClusterId()));

      // SEARCH IN LOCAL TX
      ORecord record = getTransaction().getRecord(rid);
      if (record == OTransactionRealAbstract.DELETED_RECORD)
        // DELETED IN TX
        return null;

      if (record == null && !iIgnoreCache)
        // SEARCH INTO THE CACHE
        record = getLocalCache().findRecord(rid);

      if (record != null) {
        if (iRecord != null) {
          iRecord.fromStream(record.toStream());
          iRecord.getRecordVersion().copyFrom(record.getRecordVersion());
          record = iRecord;
        }

        OFetchHelper.checkFetchPlanValid(iFetchPlan);
        if (callbackHooks(TYPE.BEFORE_READ, record) == ORecordHook.RESULT.SKIP)
          return null;

        if (record.getInternalStatus() == ORecordElement.STATUS.NOT_LOADED)
          record.reload();

        if (iLockingStrategy == OStorage.LOCKING_STRATEGY.KEEP_SHARED_LOCK)
          record.lock(false);
        else if (iLockingStrategy == OStorage.LOCKING_STRATEGY.KEEP_EXCLUSIVE_LOCK)
          record.lock(true);

        callbackHooks(TYPE.AFTER_READ, record);
        return (RET) record;
      }

      final ORawBuffer recordBuffer = underlying.read(rid, iFetchPlan, iIgnoreCache, loadTombstones, iLockingStrategy).getResult();
      if (recordBuffer == null)
        return null;

      if (iRecord == null || ORecordInternal.getRecordType(iRecord) != recordBuffer.recordType)
        // NO SAME RECORD TYPE: CAN'T REUSE OLD ONE BUT CREATE A NEW ONE FOR IT
        iRecord = Orient.instance().getRecordFactoryManager().newInstance(recordBuffer.recordType);

      ORecordInternal.fill(iRecord, rid, recordBuffer.version, recordBuffer.buffer, false);

      if (iRecord.getRecordVersion().isTombstone())
        return (RET) iRecord;

      if (callbackHooks(TYPE.BEFORE_READ, iRecord) == RESULT.SKIP)
        return null;

      iRecord.fromStream(recordBuffer.buffer);

      callbackHooks(TYPE.AFTER_READ, iRecord);

      if (!iIgnoreCache)
        getLocalCache().updateRecord(iRecord);

      return (RET) iRecord;
    } catch (OException e) {
      // RE-THROW THE EXCEPTION
      throw e;

    } catch (Exception e) {
      // WRAP IT AS ODATABASE EXCEPTION
      throw new ODatabaseException("Error on retrieving record " + rid, e);
    }
  }

  public <RET extends ORecord> RET executeSaveRecord(final ORecord record, String iClusterName, final ORecordVersion iVersion,
      boolean iCallTriggers, final OPERATION_MODE iMode, boolean iForceCreate,
      final ORecordCallback<? extends Number> iRecordCreatedCallback, ORecordCallback<ORecordVersion> iRecordUpdatedCallback) {
    checkOpeness();
    setCurrentDatabaseinThreadLocal();

    if (!record.isDirty())
      return (RET) record;

    final ORecordId rid = (ORecordId) record.getIdentity();

    if (rid == null)
      throw new ODatabaseException(
          "Cannot create record because it has no identity. Probably is not a regular record or contains projections of fields rather than a full record");

    final Set<OIndex<?>> lockedIndexes = new HashSet<OIndex<?>>();
    record.setInternalStatus(ORecordElement.STATUS.MARSHALLING);
    try {
      if (record instanceof ODocument)
        acquireIndexModificationLock((ODocument) record, lockedIndexes);

      final boolean wasNew = iForceCreate || rid.isNew();

      if (wasNew && rid.clusterId == -1)
        // ASSIGN THE CLUSTER ID
        rid.clusterId = iClusterName != null ? getClusterIdByName(iClusterName) : getDefaultClusterId();

      byte[] stream;
      final OStorageOperationResult<ORecordVersion> operationResult;

      ORecordSerializationContext.pushContext();
      try {
        // STREAM.LENGTH == 0 -> RECORD IN STACK: WILL BE SAVED AFTER
        stream = record.toStream();

        final boolean isNew = iForceCreate || rid.isNew();
        if (isNew)
          // NOTIFY IDENTITY HAS CHANGED
          record.onBeforeIdentityChanged(record);
        else if (stream == null || stream.length == 0)
          // ALREADY CREATED AND WAITING FOR THE RIGHT UPDATE (WE'RE IN A GRAPH)
          return (RET) record;

        if (isNew && rid.clusterId < 0)
          rid.clusterId = iClusterName != null ? getClusterIdByName(iClusterName) : getDefaultClusterId();

        if (rid.clusterId > -1 && iClusterName == null)
          iClusterName = getClusterNameById(rid.clusterId);

        checkRecordClass(record, iClusterName, rid, isNew);

        checkSecurity(ODatabaseSecurityResources.CLUSTER, wasNew ? ORole.PERMISSION_CREATE : ORole.PERMISSION_UPDATE, iClusterName);

        if (stream != null && stream.length > 0) {
          if (iCallTriggers) {
            final TYPE triggerType = wasNew ? TYPE.BEFORE_CREATE : TYPE.BEFORE_UPDATE;

            final RESULT hookResult = callbackHooks(triggerType, record);
            if (hookResult == RESULT.RECORD_CHANGED)
              stream = updateStream(record);
            else if (hookResult == RESULT.SKIP_IO)
              return (RET) record;
            else if (hookResult == RESULT.RECORD_REPLACED)
              // RETURNED THE REPLACED RECORD
              return (RET) OHookReplacedRecordThreadLocal.INSTANCE.get();
          }
        }

        if (!record.isDirty())
          return (RET) record;

        // CHECK IF ENABLE THE MVCC OR BYPASS IT
        final ORecordVersion realVersion = !mvcc || iVersion.isUntracked() ? OVersionFactory.instance().createUntrackedVersion()
            : record.getRecordVersion();

        try {
          // SAVE IT
          operationResult = underlying.save(rid, ORecordInternal.isContentChanged(record),
              stream == null ? new byte[0] : stream, realVersion, ORecordInternal.getRecordType(record), iMode.ordinal(),
              iForceCreate, iRecordCreatedCallback, iRecordUpdatedCallback);

          final ORecordVersion version = operationResult.getResult();

          if (isNew) {
            // UPDATE INFORMATION: CLUSTER ID+POSITION
            ((ORecordId) record.getIdentity()).copyFrom(rid);
            // NOTIFY IDENTITY HAS CHANGED
            record.onAfterIdentityChanged(record);
            // UPDATE INFORMATION: CLUSTER ID+POSITION
          }

          if (operationResult.getModifiedRecordContent() != null)
            stream = operationResult.getModifiedRecordContent();

          ORecordInternal.fill(record, rid, version, stream, stream == null || stream.length == 0);

          callbackHookSuccess(record, iCallTriggers, wasNew, stream, operationResult);
        } catch (Throwable t) {
          callbackHookFailure(record, iCallTriggers, wasNew, stream);
          throw t;
        }
      } finally {
        ORecordSerializationContext.pullContext();
      }

      if (stream != null && stream.length > 0 && !operationResult.isMoved())
        // ADD/UPDATE IT IN CACHE IF IT'S ACTIVE
        getLocalCache().updateRecord(record);
    } catch (OException e) {
      throw e;
    } catch (Throwable t) {
      if (!record.getIdentity().getClusterPosition().isValid())
        throw new ODatabaseException("Error on saving record in cluster #" + record.getIdentity().getClusterId(), t);
      else
        throw new ODatabaseException("Error on saving record " + record.getIdentity(), t);

    } finally {
      releaseIndexModificationLock(lockedIndexes);
      record.setInternalStatus(ORecordElement.STATUS.LOADED);
    }
    return (RET) record;
  }

  public void executeDeleteRecord(OIdentifiable record, final ORecordVersion iVersion, final boolean iRequired,
      boolean iCallTriggers, final OPERATION_MODE iMode, boolean prohibitTombstones) {
    checkOpeness();
    final ORecordId rid = (ORecordId) record.getIdentity();

    if (rid == null)
      throw new ODatabaseException(
          "Cannot delete record because it has no identity. Probably was created from scratch or contains projections of fields rather than a full record");

    if (!rid.isValid())
      return;

    record = record.getRecord();
    if (record == null)
      return;

    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_DELETE, getClusterNameById(rid.clusterId));

    final Set<OIndex<?>> lockedIndexes = new HashSet<OIndex<?>>();
    setCurrentDatabaseinThreadLocal();
    ORecordSerializationContext.pushContext();
    try {
      if (record instanceof ODocument)
        acquireIndexModificationLock((ODocument) record, lockedIndexes);

      try {
        // if cache is switched off record will be unreachable after delete.
        ORecord rec = record.getRecord();
        if (iCallTriggers && rec != null)
          callbackHooks(TYPE.BEFORE_DELETE, rec);

        // CHECK IF ENABLE THE MVCC OR BYPASS IT
        final ORecordVersion realVersion = mvcc ? iVersion : OVersionFactory.instance().createUntrackedVersion();

        final OStorageOperationResult<Boolean> operationResult;
        try {
          if (prohibitTombstones)
            operationResult = new OStorageOperationResult<Boolean>(underlying.cleanOutRecord(rid, realVersion, iRequired,
                (byte) iMode.ordinal()));
          else
            operationResult = underlying.delete(rid, realVersion, iRequired, (byte) iMode.ordinal());

          if (iCallTriggers) {
            if (!operationResult.isMoved() && rec != null)
              callbackHooks(TYPE.AFTER_DELETE, rec);
            else if (rec != null)
              callbackHooks(TYPE.DELETE_REPLICATED, rec);
          }
        } catch (Throwable t) {
          if (iCallTriggers)
            callbackHooks(TYPE.DELETE_FAILED, rec);
          throw t;
        }

        // REMOVE THE RECORD FROM 1 AND 2 LEVEL CACHES
        if (!operationResult.isMoved()) {
          getLocalCache().deleteRecord(rid);
        }

      } catch (OException e) {
        // RE-THROW THE EXCEPTION
        throw e;

      } catch (Throwable t) {
        // WRAP IT AS ODATABASE EXCEPTION
        throw new ODatabaseException("Error on deleting record in cluster #" + record.getIdentity().getClusterId(), t);
      }
    } finally {
      releaseIndexModificationLock(lockedIndexes);
      ORecordSerializationContext.pullContext();
    }
  }

  public boolean executeHideRecord(OIdentifiable record, final OPERATION_MODE iMode) {
    checkOpeness();
    final ORecordId rid = (ORecordId) record.getIdentity();

    if (rid == null)
      throw new ODatabaseException(
          "Cannot hide record because it has no identity. Probably was created from scratch or contains projections of fields rather than a full record");

    if (!rid.isValid())
      return false;

    checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_DELETE, getClusterNameById(rid.clusterId));

    setCurrentDatabaseinThreadLocal();
    ORecordSerializationContext.pushContext();
    try {

      final OStorageOperationResult<Boolean> operationResult;
      operationResult = underlying.hide(rid, (byte) iMode.ordinal());

      // REMOVE THE RECORD FROM 1 AND 2 LEVEL CACHES
      if (!operationResult.isMoved())
        getLocalCache().deleteRecord(rid);

      return operationResult.getResult();
    } finally {
      ORecordSerializationContext.pullContext();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ODatabaseComplexInternal<?> getDatabaseOwner() {
    ODatabaseComplexInternal<?> current = databaseOwner;

    while (current != null && current != this && current.getDatabaseOwner() != current)
      current = current.getDatabaseOwner();

    return current;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ODatabaseComplexInternal<ORecord> setDatabaseOwner(ODatabaseComplexInternal<?> iOwner) {
    databaseOwner = iOwner;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public boolean isRetainRecords() {
    return retainRecords;
  }

  /**
   * {@inheritDoc}
   */
  public ODatabaseRecord setRetainRecords(boolean retainRecords) {
    this.retainRecords = retainRecords;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabase> DB setStatus(final STATUS status) {
    final String cmd = String.format("alter database status %s", status.toString());
    command(new OCommandSQL(cmd)).execute();
    return (DB) this;
  }

  public void setStatusInternal(final STATUS status) {
    underlying.setStatus(status);
  }

  public void setDefaultClusterIdInternal(final int iDefClusterId) {
    getStorage().setDefaultClusterId(iDefClusterId);
  }

  /**
   * {@inheritDoc}
   */
  public void setInternal(final ATTRIBUTES iAttribute, final Object iValue) {
    underlying.set(iAttribute, iValue);
  }

  /**
   * {@inheritDoc}
   */
  public OUser getUser() {
    return user;
  }

  /**
   * {@inheritDoc}
   */
  public void setUser(OUser user) {
    this.user = user;
  }

  /**
   * {@inheritDoc}
   */
  public boolean isMVCC() {
    return mvcc;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseComplex<?>> DB setMVCC(boolean mvcc) {
    this.mvcc = mvcc;
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseComplex<?>> DB registerHook(final ORecordHook iHookImpl, final ORecordHook.HOOK_POSITION iPosition) {
    final Map<ORecordHook, ORecordHook.HOOK_POSITION> tmp = new LinkedHashMap<ORecordHook, ORecordHook.HOOK_POSITION>(hooks);
    tmp.put(iHookImpl, iPosition);
    hooks.clear();
    for (ORecordHook.HOOK_POSITION p : ORecordHook.HOOK_POSITION.values()) {
      for (Map.Entry<ORecordHook, ORecordHook.HOOK_POSITION> e : tmp.entrySet()) {
        if (e.getValue() == p)
          hooks.put(e.getKey(), e.getValue());
      }
    }
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseComplex<?>> DB registerHook(final ORecordHook iHookImpl) {
    return (DB) registerHook(iHookImpl, ORecordHook.HOOK_POSITION.REGULAR);
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseComplex<?>> DB unregisterHook(final ORecordHook iHookImpl) {
    if (iHookImpl != null) {
      iHookImpl.onUnregister();
      hooks.remove(iHookImpl);
    }
    return (DB) this;
  }

  /**
   * {@inheritDoc}
   */
  public OSBTreeCollectionManager getSbTreeCollectionManager() {
    return sbTreeCollectionManager;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public OLocalRecordCache getLocalCache() {
    return level1Cache;
  }

  /**
   * {@inheritDoc}
   */
  public Map<ORecordHook, ORecordHook.HOOK_POSITION> getHooks() {
    return unmodifiableHooks;
  }

  /**
   * Callback the registeted hooks if any.
   * 
   * @param iType
   *          Hook type. Define when hook is called.
   * @param id
   *          Record received in the callback
   * @return True if the input record is changed, otherwise false
   */
  public ORecordHook.RESULT callbackHooks(final TYPE iType, final OIdentifiable id) {
    if (id == null || !OHookThreadLocal.INSTANCE.push(id))
      return RESULT.RECORD_NOT_CHANGED;

    try {
      final ORecord rec = id.getRecord();
      if (rec == null)
        return RESULT.RECORD_NOT_CHANGED;

      RUN_MODE runMode = OScenarioThreadLocal.INSTANCE.get();

      boolean recordChanged = false;
      for (ORecordHook hook : hooks.keySet()) {
        // CHECK IF EXECUTE THE TRIGGER BASED ON STORAGE TYPE: DISTRIBUTED OR NOT
        switch (runMode) {
        case DEFAULT: // NON_DISTRIBUTED OR PROXIED DB
          if (getStorage().isDistributed() && hook.getDistributedExecutionMode() == DISTRIBUTED_EXECUTION_MODE.TARGET_NODE)
            // SKIP
            continue;
          break; // TARGET NODE
        case RUNNING_DISTRIBUTED:
          if (hook.getDistributedExecutionMode() == DISTRIBUTED_EXECUTION_MODE.SOURCE_NODE)
            continue;
        }

        final RESULT res = hook.onTrigger(iType, rec);

        if (res == RESULT.RECORD_CHANGED)
          recordChanged = true;
        else if (res == RESULT.SKIP_IO)
          // SKIP IO OPERATION
          return res;
        else if (res == RESULT.SKIP)
          // SKIP NEXT HOOKS AND RETURN IT
          return res;
        else if (res == RESULT.RECORD_REPLACED)
          return res;
      }

      return recordChanged ? RESULT.RECORD_CHANGED : RESULT.RECORD_NOT_CHANGED;

    } finally {
      OHookThreadLocal.INSTANCE.pop(id);
    }
  }

  /**
   * {@inheritDoc}
   */
  public boolean isValidationEnabled() {
    return !getStatus().equals(STATUS.IMPORTING) && validation;
  }

  /**
   * {@inheritDoc}
   */
  public <DB extends ODatabaseRecord> DB setValidationEnabled(final boolean iEnabled) {
    validation = iEnabled;
    return (DB) this;
  }

  public void callOnOpenListeners() {
    // WAKE UP DB LIFECYCLE LISTENER
    for (Iterator<ODatabaseLifecycleListener> it = Orient.instance().getDbLifecycleListeners(); it.hasNext();)
      it.next().onOpen(getDatabaseOwner());

    // WAKE UP LISTENERS
    for (ODatabaseListener listener : underlying.getListenersCopy())
      try {
        listener.onOpen(getDatabaseOwner());
      } catch (Throwable t) {
        t.printStackTrace();
      }
  }

  public void callOnCloseListeners() {
    // WAKE UP DB LIFECYCLE LISTENER
    for (Iterator<ODatabaseLifecycleListener> it = Orient.instance().getDbLifecycleListeners(); it.hasNext();)
      it.next().onClose(getDatabaseOwner());

    // WAKE UP LISTENERS
    for (ODatabaseListener listener : underlying.getListenersCopy())
      try {
        listener.onClose(getDatabaseOwner());
      } catch (Throwable t) {
        t.printStackTrace();
      }
  }

  // Never used so can be deprecate.
  @Deprecated
  protected ORecordSerializer resolveFormat(final Object iObject) {
    return ORecordSerializerFactory.instance().getFormatForObject(iObject, recordFormat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void checkOpeness() {
    if (isClosed())
      throw new ODatabaseException("Database '" + getURL() + "' is closed");
  }

  protected void setCurrentDatabaseinThreadLocal() {
    ODatabaseRecordThreadLocal.INSTANCE.set(this);
  }

  protected void installHooks() {
    registerHook(new OClassTrigger(), ORecordHook.HOOK_POSITION.FIRST);
    registerHook(new ORestrictedAccessHook(), ORecordHook.HOOK_POSITION.FIRST);
    registerHook(new OUserTrigger(), ORecordHook.HOOK_POSITION.EARLY);
    registerHook(new OFunctionTrigger(), ORecordHook.HOOK_POSITION.REGULAR);
    registerHook(new OClassIndexManager(), ORecordHook.HOOK_POSITION.LAST);
    registerHook(new OSchedulerTrigger(), ORecordHook.HOOK_POSITION.LAST);
    registerHook(new ORidBagDeleteHook(), ORecordHook.HOOK_POSITION.LAST);
  }

  private void callbackHookFailure(ORecord record, boolean iCallTriggers, boolean wasNew, byte[] stream) {
    if (iCallTriggers && stream != null && stream.length > 0)
      callbackHooks(wasNew ? TYPE.CREATE_FAILED : TYPE.UPDATE_FAILED, record);
  }

  private void callbackHookSuccess(ORecord record, boolean iCallTriggers, boolean wasNew, byte[] stream,
      OStorageOperationResult<ORecordVersion> operationResult) {
    if (iCallTriggers && stream != null && stream.length > 0) {
      final TYPE hookType;
      if (!operationResult.isMoved()) {
        hookType = wasNew ? TYPE.AFTER_CREATE : TYPE.AFTER_UPDATE;
      } else {
        hookType = wasNew ? TYPE.CREATE_REPLICATED : TYPE.UPDATE_REPLICATED;
      }
      callbackHooks(hookType, record);
    }
  }

  private void checkRecordClass(ORecord record, String iClusterName, ORecordId rid, boolean isNew) {
    if (rid.clusterId > -1 && getStorageVersions().classesAreDetectedByClusterId() && isNew && record instanceof ODocument) {
      final ODocument recordSchemaAware = (ODocument) record;
      final OClass recordClass = recordSchemaAware.getSchemaClass();
      final OClass clusterIdClass = metadata.getSchema().getClassByClusterId(rid.clusterId);
      if (recordClass == null && clusterIdClass != null || clusterIdClass == null && recordClass != null
          || (recordClass != null && !recordClass.equals(clusterIdClass)))
        throw new OSchemaException("Record saved into cluster '" + iClusterName + "' should be saved with class '" + clusterIdClass
            + "' but has been created with class '" + recordClass + "'");
    }
  }

  private byte[] updateStream(ORecord record) {
    byte[] stream;
    ORecordInternal.unsetDirty(record);
    record.setDirty();
    ORecordSerializationContext.pullContext();
    ORecordSerializationContext.pushContext();

    stream =  record.toStream();
    return stream;
  }

  private void releaseIndexModificationLock(Set<OIndex<?>> lockedIndexes) {
    final OMetadataDefault metadata = getMetadata();
    if (metadata == null)
      return;

    final OIndexManager indexManager = metadata.getIndexManager();
    if (indexManager == null)
      return;

    for (OIndex<?> index : lockedIndexes) {
      index.getInternal().releaseModificationLock();
    }
  }

  private void acquireIndexModificationLock(ODocument doc, Set<OIndex<?>> lockedIndexes) {
    if (getStorage().getUnderlying() instanceof OStorageEmbedded) {
      final OClass cls = doc.getSchemaClass();
      if (cls != null) {
        final Collection<OIndex<?>> indexes = cls.getIndexes();
        if (indexes != null) {
          final SortedSet<OIndex<?>> indexesToLock = new TreeSet<OIndex<?>>(new Comparator<OIndex<?>>() {
            public int compare(OIndex<?> indexOne, OIndex<?> indexTwo) {
              return indexOne.getName().compareTo(indexTwo.getName());
            }
          });

          indexesToLock.addAll(indexes);

          for (final OIndex<?> index : indexesToLock) {
            index.getInternal().acquireModificationLock();
            lockedIndexes.add(index);
          }
        }
      }
    }
  }

  public ORecordConflictStrategy getConflictStrategy() {
    return getStorage().getConflictStrategy();
  }

  public ODatabaseRecordAbstract setConflictStrategy(final ORecordConflictStrategy iResolver) {
    getStorage().setConflictStrategy(iResolver);
    return this;
  }

  public ODatabaseRecordAbstract setConflictStrategy(final String iStrategyName) {
    getStorage().setConflictStrategy(Orient.instance().getRecordConflictStrategy().getStrategy(iStrategyName));
    return this;
  }
}
