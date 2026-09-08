package com.safemigrate.core.wal;

import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes PostgreSQL test_decoding output plugin messages.
 * Example formats:
 * - table public.orders: INSERT: id[bigint]:1 customer_id[character varying]:'cust_1' amount[numeric]:42.50
 * - table public.orders: UPDATE: old-key: id[bigint]:1 new-tuple: id[bigint]:1 amount[numeric]:50.00
 * - table public.orders: DELETE: id[bigint]:1
 */
public class TestDecodingDecoder implements WalMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(TestDecodingDecoder.class);

    private static final Pattern TABLE_PATTERN = Pattern.compile("^table\\s+(?:([\\w]+)\\.)?([\\w]+):\\s+(INSERT|UPDATE|DELETE):\\s*(.*)$");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("([\\w]+)\\[[^\\]]+\\]:(null|'[^']*'|[^\\s]+)");

    @Override
    public Optional<WalChangeEvent> decode(ByteBuffer buffer, long lsn) {
        if (buffer == null || !buffer.hasRemaining()) {
            return Optional.empty();
        }

        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        log.info("TestDecodingDecoder raw text: '{}'", text);

        Matcher matcher = TABLE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            // Likely transaction boundaries (e.g. BEGIN 100, COMMIT 100) or schema messages
            log.trace("Skipping non-mutation message: {}", text);
            return Optional.empty();
        }

        String tableName = matcher.group(2);
        String opString = matcher.group(3);
        String payload = matcher.group(4);

        OperationType op = OperationType.valueOf(opString);
        Map<String, Object> oldValues = new HashMap<>();
        Map<String, Object> newValues = new HashMap<>();

        if (op == OperationType.INSERT) {
            newValues = parseColumns(payload);
        } else if (op == OperationType.DELETE) {
            oldValues = parseColumns(payload.replace("old-key:", "").replace("old-tuple:", ""));
        } else if (op == OperationType.UPDATE) {
            if (payload.contains("new-tuple:")) {
                String[] parts = payload.split("new-tuple:");
                String oldPart = parts[0].replace("old-key:", "").replace("old-tuple:", "").trim();
                String newPart = parts[1].trim();
                oldValues = parseColumns(oldPart);
                newValues = parseColumns(newPart);
            } else {
                newValues = parseColumns(payload);
            }
        }

        return Optional.of(new WalChangeEvent(
                tableName,
                op,
                oldValues,
                newValues,
                lsn,
                Instant.now()
        ));
    }

    private Map<String, Object> parseColumns(String payload) {
        Map<String, Object> columns = new HashMap<>();
        Matcher colMatcher = COLUMN_PATTERN.matcher(payload);
        while (colMatcher.find()) {
            String colName = colMatcher.group(1);
            String rawVal = colMatcher.group(2);

            Object parsedVal;
            if ("null".equalsIgnoreCase(rawVal)) {
                parsedVal = null;
            } else if (rawVal.startsWith("'") && rawVal.endsWith("'") && rawVal.length() >= 2) {
                parsedVal = rawVal.substring(1, rawVal.length() - 1);
            } else {
                parsedVal = rawVal;
            }
            columns.put(colName, parsedVal);
        }
        return columns;
    }
}
