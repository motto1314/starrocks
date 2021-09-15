// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/util/Util.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common.util;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.Adler32;

public class Util {
    private static final Logger LOG = LogManager.getLogger(Util.class);
    private static final Map<PrimitiveType, String> TYPE_STRING_MAP = new HashMap<PrimitiveType, String>();

    private static final long DEFAULT_EXEC_CMD_TIMEOUT_MS = 600000L;

    private static final String[] ORDINAL_SUFFIX =
            new String[] {"th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"};

    static {
        TYPE_STRING_MAP.put(PrimitiveType.TINYINT, "tinyint(4)");
        TYPE_STRING_MAP.put(PrimitiveType.SMALLINT, "smallint(6)");
        TYPE_STRING_MAP.put(PrimitiveType.INT, "int(11)");
        TYPE_STRING_MAP.put(PrimitiveType.BIGINT, "bigint(20)");
        TYPE_STRING_MAP.put(PrimitiveType.LARGEINT, "largeint(40)");
        TYPE_STRING_MAP.put(PrimitiveType.FLOAT, "float");
        TYPE_STRING_MAP.put(PrimitiveType.DOUBLE, "double");
        TYPE_STRING_MAP.put(PrimitiveType.DATE, "date");
        TYPE_STRING_MAP.put(PrimitiveType.DATETIME, "datetime");
        TYPE_STRING_MAP.put(PrimitiveType.CHAR, "char(%d)");
        TYPE_STRING_MAP.put(PrimitiveType.VARCHAR, "varchar(%d)");
        TYPE_STRING_MAP.put(PrimitiveType.DECIMALV2, "decimal(%d,%d)");
        TYPE_STRING_MAP.put(PrimitiveType.DECIMAL32, "decimal(%d,%d)");
        TYPE_STRING_MAP.put(PrimitiveType.DECIMAL64, "decimal(%d,%d)");
        TYPE_STRING_MAP.put(PrimitiveType.DECIMAL128, "decimal(%d,%d)");
        TYPE_STRING_MAP.put(PrimitiveType.HLL, "varchar(%d)");
        TYPE_STRING_MAP.put(PrimitiveType.BOOLEAN, "bool");
        TYPE_STRING_MAP.put(PrimitiveType.BITMAP, "bitmap");
        TYPE_STRING_MAP.put(PrimitiveType.PERCENTILE, "percentile");
    }

    private static class CmdWorker extends Thread {
        private final Process process;
        private Integer exitValue;

        private StringBuffer outBuffer;
        private StringBuffer errBuffer;

        public CmdWorker(final Process process) {
            this.process = process;
            this.outBuffer = new StringBuffer();
            this.errBuffer = new StringBuffer();
        }

        public Integer getExitValue() {
            return exitValue;
        }

        public String getStdOut() {
            return this.outBuffer.toString();
        }

        public String getErrOut() {
            return this.errBuffer.toString();
        }

        @Override
        public void run() {
            BufferedReader outReader = null;
            BufferedReader errReader = null;
            String line = null;
            try {
                outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while ((line = outReader.readLine()) != null) {
                    outBuffer.append(line + '\n');
                }

                errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while ((line = errReader.readLine()) != null) {
                    errBuffer.append(line + '\n');
                }

                exitValue = process.waitFor();
            } catch (InterruptedException e) {
                LOG.warn("get exception", e);
            } catch (IOException e) {
                LOG.warn("get exception", e);
            } finally {
                try {
                    if (outReader != null) {
                        outReader.close();
                    }
                    if (errReader != null) {
                        errReader.close();
                    }
                } catch (IOException e) {
                    LOG.warn("close buffered reader error", e);
                }
            }
        }
    }

    public static CommandResult executeCommand(String cmd, String[] envp) {
        return executeCommand(cmd, envp, DEFAULT_EXEC_CMD_TIMEOUT_MS);
    }

    public static CommandResult executeCommand(String cmd, String[] envp, long timeoutMs) {
        CommandResult result = new CommandResult();
        List<String> cmdList = shellSplit(cmd);
        String[] cmds = cmdList.toArray(new String[0]);

        try {
            Process p = Runtime.getRuntime().exec(cmds, envp);
            CmdWorker cmdWorker = new CmdWorker(p);
            cmdWorker.start();

            Integer exitValue = -1;
            try {
                cmdWorker.join(timeoutMs);
                exitValue = cmdWorker.getExitValue();
                if (exitValue == null) {
                    // if we get this far then we never got an exit value from the worker thread
                    // as a result of a timeout 
                    LOG.warn("exec command [{}] timed out.", cmd);
                    exitValue = -1;
                }
            } catch (InterruptedException ex) {
                cmdWorker.interrupt();
                Thread.currentThread().interrupt();
                throw ex;
            } finally {
                p.destroy();
            }

            result.setReturnCode(exitValue);
            result.setStdout(cmdWorker.getStdOut());
            result.setStderr(cmdWorker.getErrOut());
        } catch (IOException e) {
            LOG.warn("execute command error", e);
        } catch (InterruptedException e) {
            LOG.warn("execute command error", e);
        }

        return result;
    }

    public static List<String> shellSplit(CharSequence string) {
        List<String> tokens = new ArrayList<String>();
        boolean escaping = false;
        char quoteChar = ' ';
        boolean quoting = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
            } else if (c == '\\' && !(quoting && quoteChar == '\'')) {
                escaping = true;
            } else if (quoting && c == quoteChar) {
                quoting = false;
            } else if (!quoting && (c == '\'' || c == '"')) {
                quoting = true;
                quoteChar = c;
            } else if (!quoting && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String columnHashString(Column column) {
        Type type = column.getType();
        if (type.isScalarType()) {
            PrimitiveType primitiveType = type.getPrimitiveType();
            switch (primitiveType) {
                case CHAR:
                case VARCHAR:
                    return String.format(
                            TYPE_STRING_MAP.get(primitiveType), column.getStrLen());
                case DECIMALV2:
                case DECIMAL32:
                case DECIMAL64:
                case DECIMAL128:
                    return String.format(
                            TYPE_STRING_MAP.get(primitiveType), column.getPrecision(),
                            column.getScale());
                default:
                    return TYPE_STRING_MAP.get(primitiveType);
            }
        } else {
            return type.prettyPrint();
        }
    }

    public static int schemaHash(int schemaVersion, List<Column> columns, Set<String> bfColumns, double bfFpp) {
        Adler32 adler32 = new Adler32();
        adler32.update(schemaVersion);
        String charsetName = "UTF-8";
        try {
            List<String> indexColumnNames = Lists.newArrayList();
            List<String> bfColumnNames = Lists.newArrayList();
            // columns
            for (Column column : columns) {
                adler32.update(column.getName().getBytes(charsetName));
                String typeString = columnHashString(column);
                adler32.update(typeString.getBytes(charsetName));

                String columnName = column.getName();
                if (column.isKey()) {
                    indexColumnNames.add(columnName);
                }

                if (bfColumns != null && bfColumns.contains(columnName)) {
                    bfColumnNames.add(columnName);
                }
            }

            // index column name
            for (String columnName : indexColumnNames) {
                adler32.update(columnName.getBytes(charsetName));
            }

            // bloom filter index
            if (!bfColumnNames.isEmpty()) {
                // bf column name
                for (String columnName : bfColumnNames) {
                    adler32.update(columnName.getBytes(charsetName));
                }

                // bf fpp
                String bfFppStr = String.valueOf(bfFpp);
                adler32.update(bfFppStr.getBytes(charsetName));
            }
        } catch (UnsupportedEncodingException e) {
            LOG.error("encoding error", e);
            return -1;
        }

        return Math.abs((int) adler32.getValue());
    }

    public static long generateVersionHash() {
        return Math.abs(new Random().nextLong());
    }

    public static int generateSchemaHash() {
        return Math.abs(new Random().nextInt());
    }

    public static String dumpThread(Thread t, int lineNum) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] elements = t.getStackTrace();
        sb.append("dump thread: ").append(t.getName()).append(", id: ").append(t.getId()).append("\n");
        int count = lineNum;
        for (StackTraceElement element : elements) {
            if (count == 0) {
                break;
            }
            sb.append("    ").append(element.toString()).append("\n");
            --count;
        }
        return sb.toString();
    }

    // get response body as a string from the given url.
    // "encodedAuthInfo", the base64 encoded auth info. like:
    //      Base64.encodeBase64String("user:passwd".getBytes());
    // If no auth info, pass a null.
    public static String getResultForUrl(String urlStr, String encodedAuthInfo, int connectTimeoutMs,
                                         int readTimeoutMs) {
        StringBuilder sb = new StringBuilder();
        InputStream stream = null;
        try {
            URL url = new URL(urlStr);
            URLConnection conn = url.openConnection();
            if (encodedAuthInfo != null) {
                conn.setRequestProperty("Authorization", "Basic " + encodedAuthInfo);
            }
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            stream = (InputStream) conn.getContent();
            BufferedReader br = new BufferedReader(new InputStreamReader(stream));

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception e) {
            LOG.warn("failed to get result from url: {}. {}", urlStr, e.getMessage());
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    LOG.warn("failed to close stream when get result from url: {}", urlStr, e);
                    return null;
                }
            }
        }
        LOG.debug("get result from url {}: {}", urlStr, sb.toString());
        return sb.toString();
    }

    public static long getLongPropertyOrDefault(String valStr, long defaultVal, Predicate<Long> pred,
                                                String hintMsg) throws AnalysisException {
        if (Strings.isNullOrEmpty(valStr)) {
            return defaultVal;
        }

        long result = defaultVal;
        try {
            result = Long.valueOf(valStr);
        } catch (NumberFormatException e) {
            throw new AnalysisException(hintMsg);
        }

        if (pred == null) {
            return result;
        }

        if (!pred.test(result)) {
            throw new AnalysisException(hintMsg);
        }

        return result;
    }

    public static boolean getBooleanPropertyOrDefault(String valStr, boolean defaultVal, String hintMsg)
            throws AnalysisException {
        if (Strings.isNullOrEmpty(valStr)) {
            return defaultVal;
        }

        try {
            return Boolean.parseBoolean(valStr);
        } catch (NumberFormatException e) {
            throw new AnalysisException(hintMsg);
        }
    }

    public static void stdoutWithTime(String msg) {
        System.out.println("[" + TimeUtils.longToTimeString(System.currentTimeMillis()) + "] " + msg);
    }

    // return the ordinal string of an Integer
    public static String ordinal(int i) {
        switch (i % 100) {
            case 11:
            case 12:
            case 13:
                return i + "th";
            default:
                return i + ORDINAL_SUFFIX[i % 10];
        }
    }

    // get an input stream from url, the caller is responsible for closing the stream
    // "encodedAuthInfo", the base64 encoded auth info. like:
    //      Base64.encodeBase64String("user:passwd".getBytes());
    // If no auth info, pass a null.
    public static InputStream getInputStreamFromUrl(String urlStr, String encodedAuthInfo, int connectTimeoutMs,
                                                    int readTimeoutMs) throws IOException {
        URL url = new URL(urlStr);
        URLConnection conn = url.openConnection();
        if (encodedAuthInfo != null) {
            conn.setRequestProperty("Authorization", "Basic " + encodedAuthInfo);
        }
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        return conn.getInputStream();
    }
}

