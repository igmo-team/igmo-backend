package com.igmo.service;

public interface ImageStorageClient {

    String store(byte[] image, String contentType);
}
