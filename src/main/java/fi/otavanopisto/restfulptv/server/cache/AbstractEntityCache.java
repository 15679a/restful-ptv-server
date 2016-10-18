package fi.otavanopisto.restfulptv.server.cache;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Resource;
import javax.inject.Inject;

import org.infinispan.Cache;
import org.infinispan.manager.CacheContainer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Abstract base cache for all PTV entity caches
 * 
 * @author Antti Leppä
 * @author Heikki Kurhinen
 */
@SuppressWarnings ("squid:S3306")
public abstract class AbstractEntityCache <T> implements Serializable {
  
  private static final long serialVersionUID = -2187920247572569941L;
  
  @Inject
  private transient Logger logger;

  @Resource (lookup = "java:jboss/infinispan/container/kunta-api")
  private transient CacheContainer cacheContainer;
  
  public abstract String getCacheName();

  public Cache<String, String> getCache() {
    return cacheContainer.getCache(getCacheName());
  }
  
  public int indexOf(String id) {
    Cache<Object, Object> cache = cacheContainer.getCache("indexcache");
    String cacheKey = String.format("%s-%s", getCacheName(), id);
    if (cache.containsKey(cacheKey)) {
      Integer index = (Integer) cache.get(cacheKey);
      if (index != null) {
        return index;
      }
    }
    
    return Integer.MAX_VALUE;
  }
  
  public void assignIndex(String id) {
    Cache<Object, Object> cache = cacheContainer.getCache("indexcache");
    String cacheKey = String.format("%s-%s", getCacheName(), id);
    
    if (!cache.containsKey(cacheKey)) {
      Integer index = cache.size() + 1;
      cache.put(cacheKey, index);
    }
  }
  
  public boolean has(String id) {
    return getCache().containsKey(id);
  }
  
  /**
   * Returns cached entity by id
   * 
   * @param id entity id
   * @return cached api reposponse or null if non found
   */
  public T get(String id) {
    Cache<String, String> cache = getCache();
    if (cache.containsKey(id)) {
      String rawData = cache.get(id);
      if (rawData == null) {
        logger.log(Level.SEVERE, String.format("Could not find data for id %s", id));
        return null;
      }
      
      ObjectMapper objectMapper = new ObjectMapper();
      try {
        return objectMapper.readValue(rawData, getTypeReference());
      } catch (IOException e) {
        cache.remove(id);
        logger.log(Level.SEVERE, "Invalid serizalized object found from the cache. Dropped object", e);
      }
    }
    
    return null;
  }
  
  /**
   * Caches an entity
   * 
   * @param id entity id
   * @param response
   */
  public void put(String id, T response) {
    Cache<String, String> cache = getCache();
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      cache.put(id, objectMapper.writeValueAsString(response));
      assignIndex(id);
    } catch (JsonProcessingException e) {
      logger.log(Level.SEVERE, "Failed to serialize response into cache", e);
    }
  }
  
  /**
   * Removes elements from the cache
   * 
   * @param id entity id
   */
  public void clear(String id) {
    Cache<String, String> cache = getCache();
    cache.remove(id);
  }
  
  /**
   * Returns all cached ids
   * 
   * @return  all cached ids
   */
  public List<String> getIds() {
    Cache<String, String> cache = getCache();
    ArrayList<String> result = new ArrayList<>(cache.keySet());
    
    Collections.sort(result, new KeyComparator());
    
    return result;
  }
  
  private TypeReference<T> getTypeReference() {    
    Type superClass = getClass().getGenericSuperclass();
    if (superClass instanceof ParameterizedType) {
      final Type parameterizedType = ((ParameterizedType) superClass).getActualTypeArguments()[0];
      return new TypeReference<T>() {
        @Override
        public Type getType() {
          return parameterizedType;
        }
      };
    }
    return null;
  }
  
  private class KeyComparator implements Comparator<String> {
    
    @Override
    public int compare(String key1, String key2) {
      int key1Index = indexOf(key1);
      int key2Index = indexOf(key2);
      
      if (key1Index > key2Index) {
        return 1;
      } else if (key1Index < key2Index) {
        return -1;
      }
      
      return 0;
    }
    
  }
  
}
