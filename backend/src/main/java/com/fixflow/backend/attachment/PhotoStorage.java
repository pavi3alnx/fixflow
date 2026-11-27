package com.fixflow.backend.attachment;

public interface PhotoStorage {
    String provider();
    void put(String key,byte[] bytes,String mediaType);
    byte[] get(String key);
    void delete(String key);
}
