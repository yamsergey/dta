package io.yamsergey.dta.sidekick.data;

import android.content.Context;
import android.util.Xml;

import io.yamsergey.dta.sidekick.SidekickLog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enumerates the AppFunctions a host app exposes via the
 * {@code androidx.appfunctions} framework (Android 16+).
 *
 * <p>Reads the assets file the framework's KSP compiler emits at build time
 * ({@code app_functions_v2.xml}, falling back to {@code app_functions.xml})
 * and surfaces each function's id, description, parameters, and return type.
 * No reflection into the framework runtime is required — the asset is plain
 * XML inside the APK, so this works without {@code androidx.appfunctions} on
 * the classpath and without depending on the platform's {@code AppFunctionManager}
 * service (which requires API 36+ and special permissions for cross-app
 * discovery — irrelevant here since we are in-process).
 */
public class AppFunctionsInspector {

    private static final String TAG = "AppFunctionsInspector";

    /**
     * KSP-emitted v2 manifest. Richer schema than v1 — includes parameter and
     * response data-type metadata with type codes and references.
     */
    private static final String ASSET_V2 = "app_functions_v2.xml";

    /**
     * Legacy v1 manifest. KSP still emits it for backward compat; same shape
     * but a sparser type system. Used as fallback only.
     */
    private static final String ASSET_V1 = "app_functions.xml";

    /**
     * Type codes used in {@code <type>N</type>} elements, mirrored from
     * {@code androidx.appfunctions.metadata.AppFunctionDataTypeMetadata}
     * constants (alpha08). Keys are the raw int values written into the XML;
     * values are human-readable names. The XML carries the int so a
     * forward-compat reader just falls back to the raw number when the code
     * isn't in this table.
     */
    private static final Map<Integer, String> TYPE_NAMES;
    static {
        Map<Integer, String> m = new HashMap<>();
        m.put(0, "unit");
        m.put(1, "boolean");
        m.put(2, "bytes");
        m.put(3, "object");
        m.put(4, "double");
        m.put(5, "float");
        m.put(6, "long");
        m.put(7, "int");
        m.put(8, "string");
        // 9 is unassigned in alpha08.
        m.put(10, "array");
        m.put(11, "reference");
        m.put(12, "allOf");
        m.put(13, "parcelable");
        m.put(14, "oneOf");
        TYPE_NAMES = Collections.unmodifiableMap(m);
    }

    private final Context context;

    public AppFunctionsInspector(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns the AppFunctions metadata for the current app, or a note
     * explaining why nothing is available. Always returns a populated
     * {@code functions} list (possibly empty); errors are surfaced under
     * {@code error}.
     */
    public Map<String, Object> appFunctions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageName", context.getPackageName());

        String assetName = pickAsset();
        if (assetName == null) {
            result.put("functions", Collections.emptyList());
            result.put("count", 0);
            result.put("note",
                "No app_functions_v2.xml or app_functions.xml in assets — host "
              + "app doesn't use androidx.appfunctions or the KSP compiler "
              + "wasn't run with appfunctions:aggregateAppFunctions=true.");
            return result;
        }
        result.put("manifestAsset", assetName);

        try (InputStream is = context.getAssets().open(assetName)) {
            List<Map<String, Object>> functions = parseAppFunctions(is);
            result.put("functions", functions);
            result.put("count", functions.size());
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to parse " + assetName, e);
            result.put("functions", Collections.emptyList());
            result.put("count", 0);
            result.put("error", "parse failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    /**
     * Picks the richest available manifest. Prefers v2 when both are present
     * (KSP emits both for backward compat).
     */
    private String pickAsset() {
        try {
            String[] names = context.getAssets().list("");
            if (names == null) return null;
            Set<String> set = new HashSet<>(Arrays.asList(names));
            if (set.contains(ASSET_V2)) return ASSET_V2;
            if (set.contains(ASSET_V1)) return ASSET_V1;
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Parses {@code <appfunctions><appfunction>…</appfunction>*</appfunctions>}.
     * Tolerant of unknown sibling elements (logged at debug, then skipped) to
     * keep working when KSP adds fields in a later alpha.
     */
    private List<Map<String, Object>> parseAppFunctions(InputStream is)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(is, null);
        List<Map<String, Object>> functions = new ArrayList<>();
        int event = parser.next();
        // Skip to root.
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "appfunctions".equals(parser.getName())) {
                int depth = parser.getDepth();
                int child = parser.next();
                while (!(child == XmlPullParser.END_TAG
                        && parser.getDepth() == depth
                        && "appfunctions".equals(parser.getName()))
                        && child != XmlPullParser.END_DOCUMENT) {
                    if (child == XmlPullParser.START_TAG && "appfunction".equals(parser.getName())) {
                        functions.add(parseAppFunction(parser));
                    } else if (child == XmlPullParser.START_TAG) {
                        skipSubtree(parser);
                    }
                    child = parser.next();
                }
                break;
            }
            event = parser.next();
        }
        return functions;
    }

    /**
     * Parses a single {@code <appfunction>} subtree. The expected child
     * elements (per alpha08 KSP output): {@code id, enabledByDefault,
     * description, parameters*, response, schemaCategory, schemaName,
     * schemaVersion, functionId}.
     */
    private Map<String, Object> parseAppFunction(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        Map<String, Object> fn = new LinkedHashMap<>();
        List<Map<String, Object>> params = new ArrayList<>();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG
                && parser.getDepth() == depth
                && "appfunction".equals(parser.getName()))
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                switch (name) {
                    case "id":
                    case "description":
                    case "schemaCategory":
                    case "schemaName":
                    case "functionId":
                        fn.put(name, readText(parser));
                        break;
                    case "enabledByDefault":
                        fn.put("enabledByDefault", parseBool(readText(parser)));
                        break;
                    case "schemaVersion":
                        fn.put("schemaVersion", parseLong(readText(parser)));
                        break;
                    case "parameters":
                        params.add(parseParameter(parser));
                        break;
                    case "response":
                        fn.put("response", parseResponse(parser));
                        break;
                    default:
                        skipSubtree(parser);
                }
            }
            event = parser.next();
        }
        fn.put("parameters", params);
        return fn;
    }

    /**
     * Parses one {@code <parameters>} element. Schema:
     * {@code dataTypeMetadata, id, isRequired, name, description}.
     */
    private Map<String, Object> parseParameter(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        Map<String, Object> p = new LinkedHashMap<>();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG
                && parser.getDepth() == depth
                && "parameters".equals(parser.getName()))
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                switch (name) {
                    case "name":
                    case "description":
                        p.put(name, readText(parser));
                        break;
                    case "isRequired":
                        p.put("isRequired", parseBool(readText(parser)));
                        break;
                    case "dataTypeMetadata":
                        p.put("dataType", parseDataType(parser, "dataTypeMetadata"));
                        break;
                    // `id` per-parameter is always "unused" in alpha08 output;
                    // drop it from the response so callers don't trip over it.
                    case "id":
                        readText(parser);
                        break;
                    default:
                        skipSubtree(parser);
                }
            }
            event = parser.next();
        }
        return p;
    }

    /**
     * Parses {@code <response>} — same shape as a parameter but uses
     * {@code valueType} for the type slot instead of {@code dataTypeMetadata}.
     */
    private Map<String, Object> parseResponse(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        Map<String, Object> r = new LinkedHashMap<>();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG
                && parser.getDepth() == depth
                && "response".equals(parser.getName()))
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                switch (name) {
                    case "description":
                        r.put("description", readText(parser));
                        break;
                    case "valueType":
                        r.put("dataType", parseDataType(parser, "valueType"));
                        break;
                    case "id":
                        readText(parser);
                        break;
                    default:
                        skipSubtree(parser);
                }
            }
            event = parser.next();
        }
        return r;
    }

    /**
     * Parses a {@code dataTypeMetadata}- or {@code valueType}-shaped subtree.
     * Children: {@code type} (int code), {@code isNullable}, optional
     * {@code dataTypeReference} (for {@code type=reference}). Type code is
     * surfaced both as the raw int (forward-compat) and a human name when
     * known.
     */
    private Map<String, Object> parseDataType(XmlPullParser parser, String terminator)
            throws XmlPullParserException, IOException {
        Map<String, Object> d = new LinkedHashMap<>();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG
                && parser.getDepth() == depth
                && terminator.equals(parser.getName()))
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                switch (name) {
                    case "type":
                        Long t = parseLong(readText(parser));
                        if (t != null) {
                            d.put("type", t.intValue());
                            String human = TYPE_NAMES.get(t.intValue());
                            if (human != null) d.put("typeName", human);
                        }
                        break;
                    case "isNullable":
                        d.put("isNullable", parseBool(readText(parser)));
                        break;
                    case "dataTypeReference":
                        d.put("dataTypeReference", readText(parser));
                        break;
                    case "id":
                        readText(parser);
                        break;
                    default:
                        skipSubtree(parser);
                }
            }
            event = parser.next();
        }
        return d;
    }

    /** Reads the text content of the current element, then positions on its END_TAG. */
    private String readText(XmlPullParser parser) throws XmlPullParserException, IOException {
        StringBuilder sb = new StringBuilder();
        int event = parser.next();
        while (event != XmlPullParser.END_TAG && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) {
                sb.append(parser.getText());
            }
            event = parser.next();
        }
        return sb.toString().trim();
    }

    /** Skips the current START_TAG and all its children. Idempotent on END_TAG. */
    private void skipSubtree(XmlPullParser parser) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            int event = parser.next();
            if (event == XmlPullParser.END_DOCUMENT) return;
            if (event == XmlPullParser.START_TAG) depth++;
            else if (event == XmlPullParser.END_TAG) depth--;
        }
    }

    private static Boolean parseBool(String s) {
        if (s == null) return null;
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        return null;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
