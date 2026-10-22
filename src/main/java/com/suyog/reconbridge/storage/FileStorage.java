package com.suyog.reconbridge.storage;

import java.io.IOException;
import java.io.InputStream;

public interface FileStorage {

    StoredFile store(InputStream input) throws IOException;

    void delete(StoredFile file) throws IOException;
}