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

package com.orientechnologies.common.profiler;

import com.orientechnologies.common.concur.resource.OSharedResourceAbstract;
import com.orientechnologies.common.util.OPair;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public abstract class OAbstractProfiler extends OSharedResourceAbstract implements OProfilerMBean {

  protected final Map<String, OProfilerHookValue>        hooks         = new ConcurrentHashMap<String, OProfilerHookValue>();
  protected final ConcurrentHashMap<String, String>      dictionary    = new ConcurrentHashMap<String, String>();
  protected final ConcurrentHashMap<String, METRIC_TYPE> types         = new ConcurrentHashMap<String, METRIC_TYPE>();
  protected long                                         recordingFrom = -1;

  public interface OProfilerHookValue {
    public Object getValue();
  }

  public OAbstractProfiler() {
  }

  public OAbstractProfiler(final OAbstractProfiler profiler) {
    hooks.putAll(profiler.hooks);
    dictionary.putAll(profiler.dictionary);
    types.putAll(profiler.types);
  }

  public void shutdown() {
    stopRecording();
  }

  public boolean startRecording() {
    if (isRecording())
      return false;

    recordingFrom = System.currentTimeMillis();
    return true;
  }

  public boolean stopRecording() {
    if (!isRecording())
      return false;

    recordingFrom = -1;
    return true;
  }

  public boolean isRecording() {
    return recordingFrom > -1;
  }

  public void updateCounter(final String iStatName, final String iDescription, final long iPlus) {
    updateCounter(iStatName, iDescription, iPlus, iStatName);
  }

  @Override
  public String getName() {
    return "profiler";
  }

  @Override
  public void startup() {
    startRecording();
  }

  @Override
  public String dump() {
    return null;
  }

  @Override
  public String dumpCounters() {
    return null;
  }

  @Override
  public OProfilerEntry getChrono(String string) {
    return null;
  }

  @Override
  public long startChrono() {
    return 0;
  }

  @Override
  public long stopChrono(String iName, String iDescription, long iStartTime) {
    return 0;
  }

  @Override
  public long stopChrono(String iName, String iDescription, long iStartTime, String iDictionary) {
    return 0;
  }

  @Override
  public String dumpChronos() {
    return null;
  }

  @Override
  public String[] getCountersAsString() {
    return null;
  }

  @Override
  public String[] getChronosAsString() {
    return null;
  }

  @Override
  public Date getLastReset() {
    return null;
  }

  @Override
  public void setAutoDump(int iNewValue) {
  }

  @Override
  public String metadataToJSON() {
    return null;
  }

  @Override
  public Map<String, OPair<String, METRIC_TYPE>> getMetadata() {
    final Map<String, OPair<String, METRIC_TYPE>> metadata = new HashMap<String, OPair<String, METRIC_TYPE>>();
    for (Entry<String, String> entry : dictionary.entrySet())
      metadata.put(entry.getKey(), new OPair<String, METRIC_TYPE>(entry.getValue(), types.get(entry.getKey())));
    return metadata;
  }

  public void registerHookValue(final String iName, final String iDescription, final METRIC_TYPE iType,
      final OProfilerHookValue iHookValue) {
    registerHookValue(iName, iDescription, iType, iHookValue, iName);
  }

  public void registerHookValue(final String iName, final String iDescription, final METRIC_TYPE iType,
      final OProfilerHookValue iHookValue, final String iMetadataName) {
    if (iName != null) {
      unregisterHookValue(iName);
      updateMetadata(iMetadataName, iDescription, iType);
      hooks.put(iName, iHookValue);
    }
  }

  @Override
  public void unregisterHookValue(final String iName) {
    if (iName != null)
      hooks.remove(iName);
  }

  @Override
  public String getSystemMetric(final String iMetricName) {
    final StringBuilder buffer = new StringBuilder("system.".length() + iMetricName.length() + 1);
    buffer.append("system.");
    buffer.append(iMetricName);
    return buffer.toString();
  }

  @Override
  public String getProcessMetric(final String iMetricName) {
    final StringBuilder buffer = new StringBuilder("process.".length() + iMetricName.length() + 1);
    buffer.append("process.");
    buffer.append(iMetricName);
    return buffer.toString();
  }

  @Override
  public String getDatabaseMetric(final String iDatabaseName, final String iMetricName) {
    final StringBuilder buffer = new StringBuilder(128);
    buffer.append("db.");
    buffer.append(iDatabaseName != null ? iDatabaseName : "*");
    buffer.append('.');
    buffer.append(iMetricName);
    return buffer.toString();
  }

  @Override
  public String toJSON(String command, final String iPar1) {
    return null;
  }

  /**
   * Updates the metric metadata.
   */
  protected void updateMetadata(final String iName, final String iDescription, final METRIC_TYPE iType) {
    if (iDescription != null && dictionary.putIfAbsent(iName, iDescription) == null)
      types.put(iName, iType);
  }
}
