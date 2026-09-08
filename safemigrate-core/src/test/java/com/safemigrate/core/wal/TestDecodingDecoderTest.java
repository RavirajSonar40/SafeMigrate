package com.safemigrate.core.wal;

import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestDecodingDecoderTest {

    private final TestDecodingDecoder decoder = new TestDecodingDecoder();

    @Test
    void shouldDecodeInsertEvent() {
        String message = "table public.orders: INSERT: id[bigint]:1 customer_id[character varying]:'cust_10' amount[numeric]:150.50 status[character varying]:'PENDING'";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));

        Optional<WalChangeEvent> result = decoder.decode(buffer, 12345L);

        assertThat(result).isPresent();
        WalChangeEvent event = result.get();
        assertThat(event.table()).isEqualTo("orders");
        assertThat(event.operation()).isEqualTo(OperationType.INSERT);
        assertThat(event.lsn()).isEqualTo(12345L);
        assertThat(event.newValues())
                .containsEntry("id", "1")
                .containsEntry("customer_id", "cust_10")
                .containsEntry("amount", "150.50")
                .containsEntry("status", "PENDING");
    }

    @Test
    void shouldDecodeUpdateEvent() {
        String message = "table public.orders: UPDATE: old-key: id[bigint]:1 new-tuple: id[bigint]:1 customer_id[character varying]:'cust_10' amount[numeric]:200.00 status[character varying]:'COMPLETED'";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));

        Optional<WalChangeEvent> result = decoder.decode(buffer, 12346L);

        assertThat(result).isPresent();
        WalChangeEvent event = result.get();
        assertThat(event.table()).isEqualTo("orders");
        assertThat(event.operation()).isEqualTo(OperationType.UPDATE);
        assertThat(event.oldValues()).containsEntry("id", "1");
        assertThat(event.newValues())
                .containsEntry("id", "1")
                .containsEntry("status", "COMPLETED")
                .containsEntry("amount", "200.00");
    }

    @Test
    void shouldDecodeDeleteEvent() {
        String message = "table public.orders: DELETE: id[bigint]:1";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));

        Optional<WalChangeEvent> result = decoder.decode(buffer, 12347L);

        assertThat(result).isPresent();
        WalChangeEvent event = result.get();
        assertThat(event.table()).isEqualTo("orders");
        assertThat(event.operation()).isEqualTo(OperationType.DELETE);
        assertThat(event.oldValues()).containsEntry("id", "1");
    }

    @Test
    void shouldIgnoreBeginAndCommitMessages() {
        String beginMsg = "BEGIN 501";
        ByteBuffer buffer = ByteBuffer.wrap(beginMsg.getBytes(StandardCharsets.UTF_8));
        assertThat(decoder.decode(buffer, 100L)).isEmpty();

        String commitMsg = "COMMIT 501";
        ByteBuffer commitBuffer = ByteBuffer.wrap(commitMsg.getBytes(StandardCharsets.UTF_8));
        assertThat(decoder.decode(commitBuffer, 101L)).isEmpty();
    }

    @Test
    void shouldDecodeStringWithEscapedSingleQuotes() {
        String msg = "table public.customers: INSERT: id[bigint]:42 name[character varying]:'O''Connor & Sons' notes[text]:'He said ''Hello'' and left'";
        ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));

        Optional<WalChangeEvent> result = decoder.decode(buffer, 200L);
        assertThat(result).isPresent();
        WalChangeEvent event = result.get();
        assertThat(event.newValues())
                .containsEntry("id", "42")
                .containsEntry("name", "O'Connor & Sons")
                .containsEntry("notes", "He said 'Hello' and left");
    }
}
