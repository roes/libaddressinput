/*
 * Copyright (C) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.i18n.addressinput;

import static com.android.i18n.addressinput.Util.checkNotNull;

import com.android.i18n.addressinput.JsonpRequestBuilder.AsyncCallback;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Cache for dynamic address data.
 */
public final class CacheData {

  /**
   * Used to identify the source of a log message.
   */
  private static final String TAG = "CacheData";

  /**
   * Time out value for the server to respond in millisecond.
   */
  private static final int TIMEOUT = 5000;

  /**
   * URL to get address data. You can also reset it by calling {@link #setUrl(String)}.
   */
  private String serviceUrl;

  /**
   * Storage for all dynamically retrieved data.
   */
  private final JsoMap cache;

  /**
   * CacheManager that handles caching that is needed by the client of the Address Widget.
   */
  private final ClientCacheManager clientCacheManager;

  /**
   * All requests that have been sent.
   */
  private final HashSet<String> requestedKeys = new HashSet<String>();

  /**
   * All invalid requested keys. For example, if we request a random string "asdfsdf9o", and the
   * server responds by saying this key is invalid, it will be stored here.
   */
  private final HashSet<String> badKeys = new HashSet<String>();

  /**
   * Temporary store for {@code CacheListener}s. When a key is requested and still waiting for
   * server's response, the listeners for the same key will be temporary stored here. When the
   * server responded, these listeners will be triggered and then removed.
   */
  private final HashMap<LookupKey, HashSet<CacheListener>> temporaryListenerStore =
      new HashMap<LookupKey, HashSet<CacheListener>>();

  /**
   * Creates an instance of CacheData with an empty cache, and uses no caching that is external
   * to the AddressWidget.
   */
  public CacheData() {
    this(new SimpleClientCacheManager());
  }

  /**
   * Creates an instance of CacheData with an empty cache, and uses additional caching (external
   * to the AddressWidget) specified by clientCacheManager.
   */
  public CacheData(ClientCacheManager clientCacheManager) {
    this.clientCacheManager = clientCacheManager;
    setUrl(clientCacheManager.getAddressServerUrl());
    cache = JsoMap.createEmptyJsoMap();
  }

  /**
   * This constructor is meant to be used together with external caching.
   *
   * Use case:
   *
   * After having finished using the address widget:
   * String allCachedData = getJsonString();
   * Cache (save) allCachedData wherever makes sense for your service / activity
   *
   * Before using it next time:
   * Get the saved allCachedData string
   * new ClientData(new CacheData(allCachedData))
   *
   * If you don't have any saved data you can either just pass an empty string to
   * this constructor or use the other constructor.
   *
   * @param jsonString cached data from last time the class was used
   */
  public CacheData(String jsonString) {
    clientCacheManager = new SimpleClientCacheManager();
    setUrl(clientCacheManager.getAddressServerUrl());
    JsoMap tempMap = null;
    try {
      tempMap = JsoMap.buildJsoMap(jsonString);
    } catch (JSONException jsonE) {
      // If parsing the JSON string throws an exception, default to
      // starting with an empty cache.
      Log.w(TAG, "Could not parse json string, creating empty cache instead.");
      tempMap = JsoMap.createEmptyJsoMap();
    } finally {
      cache = tempMap;
    }
  }

  /**
   * Interface for all listeners to {@link CacheData} change. This is only used when multiple
   * requests of the same key is dispatched and server has not responded yet.
   */
  private static interface CacheListener extends EventListener {

    /**
     * The function that will be called when valid data is about to be put in the cache.
     *
     * @param key the key for newly arrived data.
     */
    void onAdd(String key);
  }

  /**
   * Class to handle JSON response.
   */
  private class JsonHandler {

    /**
     * Key for the requested data.
     */
    private final String key;

    /**
     * Pre-existing data for the requested key. Null is allowed.
     */
    private final JSONObject existingJso;

    private final DataLoadListener listener;

    /**
     * Constructs a JsonHandler instance.
     *
     * @param key    The key for requested data.
     * @param oldJso Pre-existing data for this key or null.
     */
    private JsonHandler(String key, JSONObject oldJso, DataLoadListener listener) {
      checkNotNull(key);
      this.key = key;
      this.existingJso = oldJso;
      this.listener = listener;
    }

    /**
     * Saves valid responded data to the cache once data arrives, or if the key is invalid,
     * saves it in the invalid cache. If there is pre-existing data for the key, it will merge
     * the new data will the old one. It also triggers {@link DataLoadListener#dataLoadingEnd()}
     * method before it returns (even when the key is invalid, or input jso is null). This is
     * called from a background thread.
     *
     * @param map The received JSON data as a map.
     */
    private void handleJson(JsoMap map) {
      // Can this ever happen?
      if (map == null) {
        Log.w(TAG, "server returns null for key:" + key);
        badKeys.add(key);
        notifyListenersAfterJobDone(key);
        triggerDataLoadingEndIfNotNull(listener);
        return;
      }

      JSONObject json = map;
      String idKey = AddressDataKey.ID.name().toLowerCase();
      if (!json.has(idKey)) {
        Log.w(TAG, "invalid or empty data returned for key: " + key);
        badKeys.add(key);
        notifyListenersAfterJobDone(key);
        triggerDataLoadingEndIfNotNull(listener);
        return;
      }

      if (existingJso != null) {
        map.mergeData((JsoMap) existingJso);
      }

      cache.putObj(key, map);
      notifyListenersAfterJobDone(key);
      triggerDataLoadingEndIfNotNull(listener);
    }
  }

  /**
   * Sets address data server URL. Input URL cannot be null.
   *
   * @param url The service URL.
   */
  public void setUrl(String url) {
    checkNotNull(url, "Cannot set URL of address data server to null.");
    serviceUrl = url;
  }

  /**
   * Gets address data server URL.
   */
  public String getUrl() {
    return serviceUrl;
  }

  /**
   * Returns a JSON string representing the data currently stored in this cache. It can be used
   * to later create a new CacheData object containing the same cached data.
   *
   * @return a JSON string representing the data stored in this cache
   */
  public String getJsonString() {
    return cache.toString();
  }

  /**
   * Checks if key and its value is cached (Note that only valid ones are cached).
   */
  public boolean containsKey(String key) {
    return cache.containsKey(key);
  }

  // This method is called from a background thread.
  private void triggerDataLoadingEndIfNotNull(DataLoadListener listener) {
    if (listener != null) {
      listener.dataLoadingEnd();
    }
  }

  /**
   * Fetches data from server, or returns if the data is already cached. If the fetched data is
   * valid, it will be added to the cache. This method also triggers {@link
   * DataLoadListener#dataLoadingEnd()} method before it returns.
   *
   * @param existingJso Pre-existing data for this key or null if none.
   * @param listener    An optional listener to call when done.
   */
  void fetchDynamicData(final LookupKey key, JSONObject existingJso,
      final DataLoadListener listener) {
    checkNotNull(key, "null key not allowed.");

    if (listener != null) {
      listener.dataLoadingBegin();
    }

    // Key is valid and cached.
    if (cache.containsKey(key.toString())) {
      triggerDataLoadingEndIfNotNull(listener);
      return;
    }

    // Key is invalid and cached.
    if (badKeys.contains(key.toString())) {
      triggerDataLoadingEndIfNotNull(listener);
      return;
    }

    // Already requested the key, and is still waiting for server's response.
    if (!requestedKeys.add(key.toString())) {
      Log.d(TAG, "data for key " + key + " requested but not cached yet");
      addListenerToTempStore(key, new CacheListener() {
        @Override
        public void onAdd(String myKey) {
          triggerDataLoadingEndIfNotNull(listener);
        }
      });
      return;
    }

    // Key is in the cache maintained by the client of the AddressWidget.
    String dataFromClientCache = clientCacheManager.get(key.toString());
    if (dataFromClientCache != null && dataFromClientCache.length() > 0) {
      final JsonHandler handler = new JsonHandler(key.toString(),
          existingJso, listener);
      try {
        handler.handleJson(JsoMap.buildJsoMap(dataFromClientCache));
        return;
      } catch (JSONException e) {
        Log.w(TAG, "Data from client's cache is in the wrong format: "
            + dataFromClientCache);
      }
    }

    // Key is not cached yet, now sending the request to the server.
    JsonpRequestBuilder jsonp = new JsonpRequestBuilder();
    jsonp.setTimeout(TIMEOUT);
    final JsonHandler handler = new JsonHandler(key.toString(),
        existingJso, listener);
    jsonp.requestObject(serviceUrl + "/" + key.toString(),
        new AsyncCallback<JsoMap>() {
          @Override
          public void onFailure(Throwable caught) {
            Log.w(TAG, "Request for key " + key + " failed");
            requestedKeys.remove(key.toString());
            notifyListenersAfterJobDone(key.toString());
            triggerDataLoadingEndIfNotNull(listener);
          }

          @Override
          public void onSuccess(JsoMap result) {
            handler.handleJson(result);
            // Put metadata into the cache maintained by the client of the
            // AddressWidget.
            String dataRetrieved = result.toString();
            clientCacheManager.put(key.toString(), dataRetrieved);
          }
        });
  }

  /**
   * Gets region data from our compiled-in java file and stores it in the
   * cache. This is only called when data cannot be obtained from the server,
   * so there will be no pre-existing data for this key.
   */
  void getFromRegionDataConstants(final LookupKey key) {
    checkNotNull(key, "null key not allowed.");
    String data = RegionDataConstants.getCountryFormatMap().get(
        key.getValueForUpperLevelField(AddressField.COUNTRY));
    if (data != null) {
      try {
        cache.putObj(key.toString(), JsoMap.buildJsoMap(data));
      } catch (JSONException e) {
        Log.w(TAG, "Failed to parse data for key " + key
              + " from RegionDataConstants");
      }
    }
  }

  /**
   * Retrieves string data identified by key.
   *
   * @param key Non-null key. E.g., "data/US/CA".
   * @return String value for specified key.
   */
  public String get(String key) {
    checkNotNull(key, "null key not allowed");
    return cache.get(key);
  }

  /**
   * Retrieves JsoMap data identified by key.
   *
   * @param key Non-null key. E.g., "data/US/CA".
   * @return String value for specified key.
   */
  public JsoMap getObj(String key) {
    checkNotNull(key, "null key not allowed");
    return cache.getObj(key);
  }

  private void notifyListenersAfterJobDone(String key) {
    LookupKey lookupKey = new LookupKey.Builder(key).build();
    HashSet<CacheListener> listeners = temporaryListenerStore.get(lookupKey);
    if (listeners != null) {
      for (CacheListener listener : listeners) {
        listener.onAdd(key.toString());
      }
      listeners.clear();
    }
  }

  private void addListenerToTempStore(LookupKey key, CacheListener listener) {
    checkNotNull(key);
    checkNotNull(listener);
    HashSet<CacheListener> listeners = temporaryListenerStore.get(key);
    if (listeners == null) {
      listeners = new HashSet<CacheListener>();
      temporaryListenerStore.put(key, listeners);
    }
    listeners.add(listener);
  }

  /**
   * Added for testing purposes.
   * Adds a new object into the cache.
   * @param id string of the format "data/country/.." ie. "data/US/CA"
   * @param object The JSONObject to be put into cache.
   */
  void addToJsoMap(String id, JSONObject object) {
    cache.putObj(id, object);
  }

  /**
   * Added for testing purposes.
   * Checks to see if the cache is empty,
   * @return true if the internal cache is empty
   */
  boolean isEmpty() {
    return cache.length() == 0;
  }
}
