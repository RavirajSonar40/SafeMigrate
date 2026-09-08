package com.safemigrate.core.wal;

import com.safemigrate.core.model.WalChangeEvent;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Interface for decoding raw replication stream bytes into WalChangeEvent instances.
 */
public interface WalMessageDecoder {

    /**
     * Decodes a replication buffer at a specific LSN.
     *
     * @param buffer Raw byte buffer from PGReplicationStream
     * @param lsn Log Sequence Number of the record
     * @return Optional containing the decoded event, or empty if message is transactional metadata (e.g. BEGIN/COMMIT).
     */
    Optional<WalChangeEvent> decode(ByteBuffer buffer, long lsn);
}
