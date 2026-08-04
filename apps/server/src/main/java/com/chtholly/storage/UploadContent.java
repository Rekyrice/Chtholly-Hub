package com.chtholly.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Web-independent, lazily readable content supplied to the storage upload use case.
 */
public interface UploadContent {

    /**
     * Reports whether the transport considers this upload empty.
     *
     * @return whether no content was supplied
     */
    boolean isEmpty();

    /**
     * Returns the transport-declared content length.
     *
     * @return declared byte length
     */
    long size();

    /**
     * Returns the transport-declared content type.
     *
     * @return content type, or {@code null} when absent
     */
    String contentType();

    /**
     * Opens a stream for bounded header validation without consuming the later full read.
     *
     * @return a new readable stream
     * @throws IOException when the transport cannot expose the content
     */
    InputStream openStream() throws IOException;

    /**
     * Reads the complete payload after authorization and validation succeed.
     *
     * @return complete upload bytes
     * @throws IOException when the transport read fails
     */
    byte[] readAllBytes() throws IOException;
}
