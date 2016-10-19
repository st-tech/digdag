package io.digdag.standards.operator.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.treasuredata.client.TDClientHttpNotFoundException;
import com.treasuredata.client.model.TDJobRequest;
import com.treasuredata.client.model.TDJobRequestBuilder;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;
import io.digdag.client.config.ConfigFactory;
import io.digdag.client.config.ConfigKey;
import io.digdag.core.Environment;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.TaskExecutionContext;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.spi.TemplateEngine;
import io.digdag.standards.operator.DurationInterval;
import io.digdag.util.Workspace;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.RawValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.digdag.standards.operator.PollingRetryExecutor.pollingRetryExecutor;
import static io.digdag.standards.operator.td.TDOperator.escapeHiveIdent;
import static io.digdag.standards.operator.td.TDOperator.escapePrestoIdent;
import static java.nio.charset.StandardCharsets.UTF_8;

public class TdOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(TdOperatorFactory.class);

    private static final String PREVIEW = "preview";
    private static final String DOWNLOAD = "download";
    private static final String RESULT = "result";

    private static final int PREVIEW_ROWS = 20;

    private final TemplateEngine templateEngine;
    private final Map<String, String> env;

    private final Config systemConfig;

    @Inject
    public TdOperatorFactory(TemplateEngine templateEngine, @Environment Map<String, String> env, Config systemConfig)
    {
        this.templateEngine = templateEngine;
        this.env = env;
        this.systemConfig = systemConfig;
    }

    public String getType()
    {
        return "td";
    }

    @Override
    public Operator newOperator(Path projectPath, TaskRequest request)
    {
        return new TdOperator(projectPath, request);
    }

    private class TdOperator
            extends BaseTdJobOperator
    {
        private final Config params;
        private final String query;
        private final Optional<TableParam> insertInto;
        private final Optional<TableParam> createTable;
        private final int priority;
        private final Optional<String> resultUrl;
        private final int jobRetry;
        private final String engine;
        private final Optional<String> downloadFile;
        private final boolean storeLastResults;
        private final boolean preview;

        private TdOperator(Path projectPath, TaskRequest request)
        {
            super(projectPath, request, env, systemConfig);

            this.params = request.getConfig().mergeDefault(
                    request.getConfig().getNestedOrGetEmpty("td"));

            this.query = workspace.templateCommand(templateEngine, params, "query", UTF_8);

            this.insertInto = params.getOptional("insert_into", TableParam.class);
            this.createTable = params.getOptional("create_table", TableParam.class);
            if (insertInto.isPresent() && createTable.isPresent()) {
                throw new ConfigException("Setting both insert_into and create_table is invalid");
            }

            this.priority = params.get("priority", int.class, 0);  // TODO this should accept string (VERY_LOW, LOW, NORMAL, HIGH VERY_HIGH)
            this.resultUrl = params.getOptional("result_url", String.class);

            this.jobRetry = params.get("job_retry", int.class, 0);

            this.engine = params.get("engine", String.class, "presto");

            this.downloadFile = params.getOptional("download_file", String.class);
            if (downloadFile.isPresent() && (insertInto.isPresent() || createTable.isPresent())) {
                // query results become empty if INSERT INTO or CREATE TABLE query runs
                throw new ConfigException("download_file is invalid if insert_into or create_table is set");
            }

            this.storeLastResults = params.get("store_last_results", boolean.class, false);

            this.preview = params.get("preview", boolean.class, false);
        }

        @Override
        protected TaskResult processJobResult(TaskExecutionContext ctx, TDOperator op, TDJobOperator j)
        {
            downloadJobResult(j, workspace, downloadFile, state, retryInterval);

            if (preview) {
                if (insertInto.isPresent() || createTable.isPresent()) {
                    TableParam destTable = insertInto.isPresent() ? insertInto.get() : createTable.get();
                    TDJobOperator jobOperator = op.runJob(state, "previewJob", pollInterval, retryInterval, (operator, domainKey) ->
                            startSelectPreviewJob(operator, "job id " + j.getJobId(), destTable, domainKey));
                    downloadPreviewRows(jobOperator, "table " + destTable.toString(), state, retryInterval);
                }
                else {
                    downloadPreviewRows(j, "job id " + j.getJobId(), state, retryInterval);
                }
            }

            Config storeParams = buildStoreParams(request.getConfig().getFactory(), j, storeLastResults, state, retryInterval);

            return TaskResult.defaultBuilder(request)
                    .resetStoreParams(buildResetStoreParams(storeLastResults))
                    .storeParams(storeParams)
                    .build();
        }

        @Override
        protected String startJob(TaskExecutionContext ctx, TDOperator op, String domainKey)
        {
            String stmt;
            switch(engine) {
                case "presto":
                    if (insertInto.isPresent()) {
                        ensureTableCreated(op, insertInto.get());
                        stmt = insertCommandStatement("INSERT INTO " + escapePrestoTableName(insertInto.get()), query);
                    }
                    else if (createTable.isPresent()) {
                        // TODO: this is not atomic
                        String tableName = escapePrestoTableName(createTable.get());
                        stmt = insertCommandStatement(
                                "DROP TABLE IF EXISTS " + tableName + ";\n" +
                                        "CREATE TABLE " + tableName + " AS", query);
                    }
                    else {
                        stmt = query;
                    }
                    break;

                case "hive":
                    if (insertInto.isPresent()) {
                        ensureTableCreated(op, insertInto.get());
                        stmt = insertCommandStatement("INSERT INTO TABLE " + escapeHiveTableName(insertInto.get()), query);
                    }
                    else if (createTable.isPresent()) {
                        ensureTableCreated(op, createTable.get());
                        stmt = insertCommandStatement("INSERT OVERWRITE TABLE " + escapeHiveTableName(createTable.get()), query);
                    }
                    else {
                        stmt = query;
                    }
                    break;

                default:
                    throw new ConfigException("Unknown 'engine:' option (available options are: hive and presto): "+engine);
            }

            TDJobRequest req = new TDJobRequestBuilder()
                    .setResultOutput(resultUrl.orNull())
                    .setType(engine)
                    .setDatabase(op.getDatabase())
                    .setQuery(stmt)
                    .setRetryLimit(jobRetry)
                    .setPriority(priority)
                    .setScheduledTime(request.getSessionTime().getEpochSecond())
                    .setDomainKey(domainKey)
                    .createTDJobRequest();

            String jobId = op.submitNewJobWithRetry(req);
            logger.info("Started {} job id={}:\n{}", engine, jobId, stmt);

            return jobId;
        }
    }

    private static String startSelectPreviewJob(TDOperator op, String description, TableParam destTable, String domainKey)
    {
        String comment = "-- preview results of " + description;

        TDJobRequest req = new TDJobRequestBuilder()
            .setType("presto")
            .setDatabase(op.getDatabase())
            .setQuery(comment + "\nSELECT * FROM " + escapePrestoTableName(destTable) + " LIMIT 20")
            .setPriority(0)
            .setDomainKey(domainKey)
            .createTDJobRequest();

        return op.submitNewJobWithRetry(req);
    }

    static void downloadPreviewRows(TDJobOperator j, String description, Config state, DurationInterval retryInterval)
    {
        StringWriter out = new StringWriter();

        try {
            addCsvHeader(out, j.getResultColumnNames());

            List<ArrayValue> rows = downloadFirstResults(j, PREVIEW_ROWS, state, PREVIEW, retryInterval);
            if (rows.isEmpty()) {
                logger.info("preview of {}: (no results)", description, j.getJobId());
                return;
            }
            for (ArrayValue row : rows) {
                addCsvRow(out, row);
            }
        }
        catch (Exception ex) {
            logger.warn("Getting rows for preview failed. Ignoring this error.", ex);
            return;
        }

        logger.info("preview of {}:\r\n{}", description, out.toString());
    }

    private final static Pattern INSERT_LINE_PATTERN = Pattern.compile("(\\A|\\r?\\n)\\-\\-\\s*DIGDAG_INSERT_LINE(?:(?!\\n|\\z).)*", Pattern.MULTILINE);

    private final static Pattern HEADER_COMMENT_BLOCK_PATTERN = Pattern.compile("\\A([\\r\\n\\t]*(?:(?:\\A|\\n)\\-\\-[^\\n]*)+)\\n?(.*)\\z", Pattern.MULTILINE);

    @VisibleForTesting
    static String insertCommandStatement(String command, String original)
    {
        // try to insert command at "-- DIGDAG_INSERT_LINE" line
        Matcher ml = INSERT_LINE_PATTERN.matcher(original);
        if (ml.find()) {
            return ml.replaceFirst(ml.group(1) + command);
        }

        // try to insert command after header comments so that job list page
        // shows comments rather than INSERT or other non-informative commands
        Matcher mc = HEADER_COMMENT_BLOCK_PATTERN.matcher(original);
        if (mc.find()) {
            return mc.group(1) + "\n" + command + "\n" + mc.group(2);
        }

        // add command at the head
        return command + "\n" + original;
    }

    private static void ensureTableDeleted(TDOperator op, TableParam table)
    {
        op.withDatabase(table.getDatabase().or(op.getDatabase())).ensureTableDeleted(table.getTable());
    }

    private static void ensureTableCreated(TDOperator op, TableParam table)
    {
        op.withDatabase(table.getDatabase().or(op.getDatabase())).ensureTableCreated(table.getTable());
    }

    private static String escapeHiveTableName(TableParam table)
    {
        if (table.getDatabase().isPresent()) {
            return escapeHiveIdent(table.getDatabase().get()) + '.' + escapeHiveIdent(table.getTable());
        }
        else {
            return escapeHiveIdent(table.getTable());
        }
    }

    private static String escapePrestoTableName(TableParam table)
    {
        if (table.getDatabase().isPresent()) {
            return escapePrestoIdent(table.getDatabase().get()) + '.' + escapePrestoIdent(table.getTable());
        }
        else {
            return escapePrestoIdent(table.getTable());
        }
    }

    static void downloadJobResult(TDJobOperator j, Workspace workspace, Optional<String> downloadFile, Config state, DurationInterval retryInterval)
    {
        if (!downloadFile.isPresent()) {
            return;
        }

        pollingRetryExecutor(state, state, DOWNLOAD)
                .retryUnless(TDOperator::isDeterministicClientException)
                .withRetryInterval(retryInterval)
                .withErrorMessage("Failed to download result of job '%s'", j.getJobId())
                .runOnce(() -> j.getResult(ite -> {
                    try (BufferedWriter out = workspace.newBufferedWriter(downloadFile.get(), UTF_8)) {
                        addCsvHeader(out, j.getResultColumnNames());
                        while (ite.hasNext()) {
                            addCsvRow(out, ite.next().asArrayValue());
                        }
                        return true;
                    }
                    catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }));
    }


    private static void addCsvHeader(Writer out, List<String> columnNames)
        throws IOException
    {
        boolean first = true;
        for (String columnName : columnNames) {
            if (first) { first = false; }
            else { out.write(DELIMITER_CHAR); }
            addCsvText(out, columnName);
        }
        out.write("\r\n");
    }

    private static void addCsvRow(Writer out, ArrayValue row)
        throws IOException
    {
        boolean first = true;
        for (Value v : row) {
            if (first) { first = false; }
            else { out.write(DELIMITER_CHAR); }
            addCsvValue(out, v);
        }
        out.write("\r\n");
    }

    static Config buildStoreParams(ConfigFactory cf, TDJobOperator j, boolean storeLastResults, Config state, DurationInterval retryInterval)
    {
        if (storeLastResults) {
            Config td = cf.create();

            List<ArrayValue> results = downloadFirstResults(j, 1, state, RESULT, retryInterval);
            Map<RawValue, Value> map = new LinkedHashMap<>();
            if (!results.isEmpty()) {
                ArrayValue row = results.get(0);
                List<String> columnNames = j.getResultColumnNames();
                for (int i = 0; i < Math.min(row.size(), columnNames.size()); i++) {
                    map.put(ValueFactory.newString(columnNames.get(i)), row.get(i));
                }
            }
            MapValue lastResults = ValueFactory.newMap(map);
            try {
                td.set("last_results", new ObjectMapper().readTree(lastResults.toJson()));
            }
            catch (IOException ex) {
                throw Throwables.propagate(ex);
            }

            return cf.create().set("td", td);
        }
        else {
            return cf.create();
        }
    }

    static List<ConfigKey> buildResetStoreParams(boolean storeLastResults)
    {
        if (storeLastResults) {
            return ImmutableList.of(ConfigKey.of("td", "last_results"));
        }
        else {
            return ImmutableList.of();
        }
    }

    private static List<ArrayValue> downloadFirstResults(TDJobOperator j, int max, Config state, String stateKey, DurationInterval retryInterval)
    {
        return pollingRetryExecutor(state, state, stateKey)
                .retryUnless(TDOperator::isDeterministicClientException)
                .withRetryInterval(retryInterval)
                .withErrorMessage("Failed to download result of job '%s'", j.getJobId())
                .run(() -> {
                    try {
                        return j.getResult(ite -> {
                            List<ArrayValue> results = new ArrayList<>(max);
                            for (int i = 0; i < max; i++) {
                                if (ite.hasNext()) {
                                    ArrayValue row = ite.next().asArrayValue();
                                    results.add(row);
                                }
                                else {
                                    break;
                                }
                            }
                            return results;
                        });
                    }
                    catch (TDClientHttpNotFoundException ex) {
                        // this happens if query is INSERT or CREATE. return empty results
                        return ImmutableList.of();
                    }
                });
    }

    private static void addCsvValue(Writer out, Value value)
        throws IOException
    {
        if (value.isStringValue()) {
            addCsvText(out, value.asStringValue().asString());
        }
        else if (value.isNilValue()) {
            // write nothing
        }
        else {
            addCsvText(out, value.toJson());
        }
    }

    private static void addCsvText(Writer out, String value)
        throws IOException
    {
        out.write(escapeAndQuoteCsvValue(value));
    }

    private static final char DELIMITER_CHAR = ',';
    private static final char ESCAPE_CHAR = '"';
    private static final char QUOTE_CHAR = '"';

    private static String escapeAndQuoteCsvValue(String v)
    {
        if (v.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(QUOTE_CHAR);
            sb.append(QUOTE_CHAR);
            return sb.toString();
        }

        StringBuilder escapedValue = new StringBuilder();
        char previousChar = ' ';

        boolean isRequireQuote = false;

        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);

            if (c == QUOTE_CHAR) {
                escapedValue.append(ESCAPE_CHAR);
                escapedValue.append(c);
                isRequireQuote = true;
            }
            else if (c == '\r') {
                escapedValue.append('\n');
                isRequireQuote = true;
            }
            else if (c == '\n') {
                if (previousChar != '\r') {
                    escapedValue.append('\n');
                    isRequireQuote = true;
                }
            }
            else if (c == DELIMITER_CHAR) {
                escapedValue.append(c);
                isRequireQuote = true;
            }
            else {
                escapedValue.append(c);
            }
            previousChar = c;
        }

        if (isRequireQuote) {
            StringBuilder sb = new StringBuilder();
            sb.append(QUOTE_CHAR);
            sb.append(escapedValue);
            sb.append(QUOTE_CHAR);
            return sb.toString();
        }
        else {
            return escapedValue.toString();
        }
    }
}
