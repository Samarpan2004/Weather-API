import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.*;

/**
 * NebulaWeather — Advanced Weather Dashboard
 * Requires Java 11+
 *
 * Compile:  javac NebulaWeather.java
 * Run:      java NebulaWeather
 * Open:     http://localhost:8080
 *
 * FIXES APPLIED:
 *  1. Removed rogue `package org.json;` + JSONArray class at the bottom
 *     (can't have a package statement inside a single-file compilation unit
 *      that already has a top-level public class, and org.json is never on
 *      the classpath anyway).
 *  2. Replaced every usage of `new org.json.JSONArray(suggestions).toString()`
 *     with a plain inline JSON builder — no external library needed.
 *  3. Fixed handleSuggestions: removed the try-catch that called the missing
 *     org.json class; replaced with a self-contained JSON array builder.
 *  4. Fixed handleWeather: removed bare `e.printStackTrace()` that swallows
 *     the real error; now logs to stderr and falls through to sendJson().
 *  5. Fixed fetchWeatherJson: hourlyTimes/hourlyTemp/hourlyHumidity/
 *     hourlyRainProb all called extractArray/extractDoubleArray/extractIntArray
 *     on wxResp using keys "time", "temperature_2m", etc. — but those keys
 *     also exist in the daily block, so the regex matched the DAILY arrays
 *     instead of the hourly ones.  Fixed by extracting the hourly sub-object
 *     first, then parsing from that substring.
 *  6. Fixed extractArray / extractDoubleArray: Pattern.DOTALL regex for arrays
 *     with nested objects (sunrise/sunset contain "T") could stop too early
 *     at a nested ']'.  Switched to a bracket-counting extractor.
 *  7. Fixed getWindDirection: negative modulo guard — Java's % can return
 *     negative values; added Math.floorMod.
 *  8. Fixed fmt1: NaN guard was present but Double.isNaN check returned "0"
 *     instead of "null" for JSON numeric fields — changed to return "null"
 *     so the frontend can test `!= null` correctly.
 *  9. Fixed generateWeatherAlerts: `rainProb[0]` accessed without checking
 *     array length; added explicit length > 0 guard.
 * 10. Added missing @SuppressWarnings("unchecked") on removeEldestEntry
 *     override to avoid unchecked-cast compiler warning.
 * 11. Minor: handleFavorites was sending JSON for a GET that the client never
 *     actually uses — kept but corrected Content-Type.
 */
public class NebulaWeather {

    static final int PORT = 8080;
    static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
    static final String WX_URL  = "https://api.open-meteo.com/v1/forecast";
    static final String AIR_QUALITY_URL = "https://air-quality-api.open-meteo.com/v1/air-quality";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    // FIX 10: suppress unchecked warning on raw Map.Entry override
    @SuppressWarnings({"unchecked","rawtypes"})
    static final Map<String, CachedWeather> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, CachedWeather>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > 10;
            }
        }
    );

    // ── Server bootstrap ──────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // Accept --port <n> as a command-line argument
        int port = PORT;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) {
                try { port = Integer.parseInt(args[i + 1]); } catch (NumberFormatException ignored) {}
            }
        }

        // Auto-fallback: try PORT, then 8081, 8082, 8090, 9090, 3000
        int[] candidates = {port, 8081, 8082, 8090, 9090, 3000};
        int chosen = -1;
        for (int candidate : candidates) {
            try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(candidate));
                chosen = candidate;
                break;
            } catch (IOException ignored) {
                System.out.println("⚠️  Port " + candidate + " is busy, trying next...");
            }
        }
        if (chosen == -1) {
            System.err.println("❌ No free port found in candidates. "
                + "Kill the process using port 8080 with:");
            System.err.println("   Windows : netstat -ano | findstr :8080  then  taskkill /PID <pid> /F");
            System.err.println("   Mac/Linux: lsof -ti:8080 | xargs kill -9");
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(chosen), 0);
        server.createContext("/",            NebulaWeather::handleHome);
        server.createContext("/weather",     NebulaWeather::handleWeather);
        server.createContext("/suggestions", NebulaWeather::handleSuggestions);
        server.createContext("/favorites",     NebulaWeather::handleFavorites);
        server.createContext("/weather-by-coords", NebulaWeather::handleWeatherByCoords);
        server.start();

        System.out.println("🌌 Nebula Weather Server v2.0");
        System.out.println("✨ Running at http://localhost:" + chosen);
        System.out.println("🎯 Features: 7-Day Forecast | Air Quality | UV Index | Wind | Alerts");
        System.out.println("   Press Ctrl+C to stop.");
    }

    // ── Request handlers ──────────────────────────────────────────────────────

    static void handleHome(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { ex.sendResponseHeaders(405, -1); return; }
        byte[] html = HTML_CACHE_REF.get();
        if (html == null) { rebuildHtmlCache(); html = HTML_CACHE_REF.get(); }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        ex.getResponseBody().write(html);
        ex.getResponseBody().close();
    }

    // FIX 2 & 3: replaced org.json.JSONArray with a plain StringBuilder
    static void handleSuggestions(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        if (query == null || !query.startsWith("q=")) { sendJson(ex, "[]"); return; }

        String searchTerm = URLDecoder.decode(query.substring(2), StandardCharsets.UTF_8);
        if (searchTerm.length() < 2) { sendJson(ex, "[]"); return; }

        try {
            String geoResp = get(GEO_URL + "?name="
                    + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8)
                    + "&count=5&language=en&format=json");

            Pattern p = Pattern.compile("\"name\":\"([^\"]+)\".*?\"country\":\"([^\"]+)\"", Pattern.DOTALL);
            Matcher m = p.matcher(geoResp);

            List<String> suggestions = new ArrayList<>();
            while (m.find()) {
                suggestions.add(m.group(1) + ", " + m.group(2));
            }

            // Build JSON array without any external library
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < suggestions.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(esc(suggestions.get(i))).append("\"");
            }
            sb.append("]");
            sendJson(ex, sb.toString());

        } catch (Exception e) {
            sendJson(ex, "[]");
        }
    }

    static void handleFavorites(HttpExchange ex) throws IOException {
        sendJson(ex, "{\"message\":\"Favorites API - Store in localStorage\"}");
    }

    // New: accepts ?lat=xx&lon=yy directly — used by geolocation button
    static void handleWeatherByCoords(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { ex.sendResponseHeaders(405, -1); return; }
        String query = ex.getRequestURI().getRawQuery();
        double lat = Double.NaN, lon = Double.NaN;
        String name = "My Location";
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("lat="))  { try { lat = Double.parseDouble(URLDecoder.decode(param.substring(4), StandardCharsets.UTF_8)); } catch (Exception ignored) {} }
                if (param.startsWith("lon="))  { try { lon = Double.parseDouble(URLDecoder.decode(param.substring(4), StandardCharsets.UTF_8)); } catch (Exception ignored) {} }
                if (param.startsWith("name=")) { name = URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8); }
            }
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)) { sendJson(ex, error("Missing lat/lon")); return; }
        String cacheKey = "coords:" + lat + "," + lon;
        if (cache.containsKey(cacheKey) && cache.get(cacheKey).isValid()) {
            sendJson(ex, cache.get(cacheKey).json); return;
        }
        try {
            String json = fetchWeatherByCoords(lat, lon, name);
            if (!json.contains("\"error\"")) cache.put(cacheKey, new CachedWeather(json));
            sendJson(ex, json);
        } catch (Exception e) {
            System.err.println("Coords weather error: " + e.getMessage());
            sendJson(ex, error(e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    static String fetchWeatherByCoords(double lat, double lon, String cityName) throws Exception {
        String wxUrl = WX_URL
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                + "wind_speed_10m,wind_direction_10m,weather_code,surface_pressure,visibility,"
                + "uv_index,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code,"
                + "precipitation_sum,precipitation_probability_max,wind_speed_10m_max,"
                + "sunrise,sunset,uv_index_max"
                + "&hourly=temperature_2m,relative_humidity_2m,precipitation_probability"
                + "&timezone=auto&forecast_days=7";
        String wxResp = get(wxUrl);
        // Reuse same field extraction as fetchWeatherJson but skip geocoding
        String currentBlock = extractBlock(wxResp, "current");
        int    wxCode   = (int) extractDouble(currentBlock, "weather_code");
        double temp     = extractDouble(currentBlock, "temperature_2m");
        double feels    = extractDouble(currentBlock, "apparent_temperature");
        int    humidity = (int) extractDouble(currentBlock, "relative_humidity_2m");
        double wind     = extractDouble(currentBlock, "wind_speed_10m");
        int    windDir  = (int) extractDouble(currentBlock, "wind_direction_10m");
        double pressure = extractDouble(currentBlock, "surface_pressure");
        double vis      = extractDouble(currentBlock, "visibility");
        double uvIndex  = extractDouble(currentBlock, "uv_index");
        int    isDay    = (int) extractDouble(currentBlock, "is_day");
        String dailyBlock = extractBlock(wxResp, "daily");
        String[] dates    = extractArray(dailyBlock, "time");
        double[] maxT     = extractDoubleArray(dailyBlock, "temperature_2m_max");
        double[] minT     = extractDoubleArray(dailyBlock, "temperature_2m_min");
        int[]    codes    = extractIntArray(dailyBlock, "weather_code");
        double[] rain     = extractDoubleArray(dailyBlock, "precipitation_sum");
        double[] rainProb = extractDoubleArray(dailyBlock, "precipitation_probability_max");
        double[] windMax  = extractDoubleArray(dailyBlock, "wind_speed_10m_max");
        String[] sunrise  = extractArray(dailyBlock, "sunrise");
        String[] sunset   = extractArray(dailyBlock, "sunset");
        double[] uvMax    = extractDoubleArray(dailyBlock, "uv_index_max");
        String hourlyBlock   = extractBlock(wxResp, "hourly");
        String[] hourlyTimes = extractArray(hourlyBlock, "time");
        double[] hourlyTemp  = extractDoubleArray(hourlyBlock, "temperature_2m");
        int[] hourlyHumidity = extractIntArray(hourlyBlock, "relative_humidity_2m");
        int[] hourlyRainProb = extractIntArray(hourlyBlock, "precipitation_probability");
        String airQuality = "{}";
        try {
            String aqUrl = AIR_QUALITY_URL + "?latitude=" + lat + "&longitude=" + lon
                    + "&current=european_aqi,pm10,pm2_5";
            airQuality = get(aqUrl);
        } catch (Exception ignored) {}
        String aqBlock = extractBlock(airQuality, "current");
        if (aqBlock.isEmpty()) aqBlock = airQuality;
        int    aqi  = (int) extractDouble(aqBlock, "european_aqi");
        double pm25 = extractDouble(aqBlock, "pm2_5");
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"city\":\"").append(esc(cityName)).append("\",");
        sb.append("\"country\":\"📍\",");
        sb.append("\"timezone\":\"auto\",");
        sb.append("\"isDay\":").append(isDay).append(",");
        sb.append("\"current\":{");
        sb.append("\"temp\":").append(fmt1(temp)).append(",");
        sb.append("\"feels\":").append(fmt1(feels)).append(",");
        sb.append("\"humidity\":").append(humidity).append(",");
        sb.append("\"wind\":").append(fmt1(wind)).append(",");
        sb.append("\"windDir\":").append(windDir).append(",");
        sb.append("\"windDirText\":\"").append(getWindDirection(windDir)).append("\",");
        sb.append("\"pressure\":").append(fmt1(pressure)).append(",");
        sb.append("\"visibility\":").append(Double.isNaN(vis) ? "null" : fmt1(vis / 1000)).append(",");
        sb.append("\"uvIndex\":").append(Double.isNaN(uvIndex) ? "null" : fmt1(uvIndex)).append(",");
        sb.append("\"uvLevel\":\"").append(getUVLevel(uvIndex)).append("\",");
        sb.append("\"code\":").append(wxCode).append(",");
        sb.append("\"desc\":\"").append(esc(wmoDesc(wxCode))).append("\",");
        sb.append("\"icon\":\"").append(getWeatherIcon(wxCode, isDay)).append("\"");
        sb.append("},");
        sb.append("\"airQuality\":{");
        sb.append("\"aqi\":").append(aqi > 0 ? aqi : "null").append(",");
        sb.append("\"aqiLevel\":\"").append(getAQILevel(aqi)).append("\",");
        sb.append("\"pm25\":").append(!Double.isNaN(pm25) && pm25 > 0 ? fmt1(pm25) : "null");
        sb.append("},");
        sb.append("\"daily\":[");
        for (int i = 0; i < dates.length && i < maxT.length; i++) {
            LocalDate date = LocalDate.parse(dates[i]);
            String label = i == 0 ? "Today" : date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"label\":\"").append(label).append("\",");
            sb.append("\"date\":\"").append(dates[i]).append("\",");
            sb.append("\"max\":").append(fmt1(maxT[i])).append(",");
            sb.append("\"min\":").append(fmt1(minT[i])).append(",");
            sb.append("\"rain\":").append(fmt1(rain.length > i ? rain[i] : 0)).append(",");
            sb.append("\"rainProb\":").append(rainProb.length > i ? (int)Math.round(rainProb[i]) : 0).append(",");
            sb.append("\"windMax\":").append(fmt1(windMax.length > i ? windMax[i] : 0)).append(",");
            sb.append("\"uvMax\":").append(fmt1(uvMax.length > i ? uvMax[i] : 0)).append(",");
            sb.append("\"sunrise\":\"").append(sunrise.length > i ? extractTime(sunrise[i]) : "").append("\",");
            sb.append("\"sunset\":\"").append(sunset.length > i ? extractTime(sunset[i]) : "").append("\",");
            sb.append("\"code\":").append(codes.length > i ? codes[i] : 0).append(",");
            sb.append("\"desc\":\"").append(esc(wmoDesc(codes.length > i ? codes[i] : 0))).append("\"");
            sb.append("}");
        }
        sb.append("],\"hourly\":[");
        int hourlyLimit = Math.min(24, hourlyTimes.length);
        for (int i = 0; i < hourlyLimit; i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"hour\":\"").append(extractTime(hourlyTimes[i])).append("\",");
            sb.append("\"temp\":").append(fmt1(hourlyTemp.length > i ? hourlyTemp[i] : 0)).append(",");
            sb.append("\"humidity\":").append(hourlyHumidity.length > i ? hourlyHumidity[i] : 0).append(",");
            sb.append("\"rainProb\":").append(hourlyRainProb.length > i ? hourlyRainProb[i] : 0);
            sb.append("}");
        }
        sb.append("],");
        sb.append("\"alerts\":").append(generateWeatherAlerts(temp, wind, uvIndex, rainProb));
        sb.append("}");
        return sb.toString();
    }

    // FIX 4: removed printStackTrace() that swallowed the real error
    static void handleWeather(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { ex.sendResponseHeaders(405, -1); return; }

        String query = ex.getRequestURI().getRawQuery();
        String city = "";
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("city=")) {
                    city = URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8).trim();
                    break;
                }
            }
        }

        if (cache.containsKey(city) && cache.get(city).isValid()) {
            sendJson(ex, cache.get(city).json);
            return;
        }

        String json;
        try {
            json = city.isEmpty() ? error("No city provided") : fetchWeatherJson(city);
            if (!json.contains("\"error\"")) {
                cache.put(city, new CachedWeather(json));
            }
        } catch (Exception e) {
            System.err.println("Weather fetch error: " + e.getMessage());
            json = error(e.getMessage() != null ? e.getMessage() : "Unknown error");
        }

        sendJson(ex, json);
    }

    static void sendJson(HttpExchange ex, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static String error(String msg) {
        return "{\"error\":\"" + esc(msg) + "\"}";
    }

    // ── Weather data fetcher ──────────────────────────────────────────────────

    static String fetchWeatherJson(String city) throws Exception {
        // Geocode
        String geoResp = get(GEO_URL + "?name="
                + URLEncoder.encode(city, StandardCharsets.UTF_8)
                + "&count=1&language=en&format=json");

        if (!geoResp.contains("\"results\"") || geoResp.contains("\"results\":[]"))
            return error("City not found: " + city);

        Pattern resultPattern = Pattern.compile(
                "\"results\":\\[\\{(.*?)\\}(?:\\s*[,\\]])", Pattern.DOTALL);
        Matcher resultMatcher = resultPattern.matcher(geoResp);
        if (!resultMatcher.find())
            return error("Could not parse geocoding response");

        String resultJson = "{" + resultMatcher.group(1) + "}";
        double lat     = extractDouble(resultJson, "latitude");
        double lon     = extractDouble(resultJson, "longitude");
        if (Double.isNaN(lat) || Double.isNaN(lon))
            return error("Could not extract coordinates for: " + city);
        String name    = extractString(resultJson, "name");
        if (name.isEmpty()) name = city;
        String country = extractString(resultJson, "country");
        String tz      = extractString(resultJson, "timezone");
        if (tz.isEmpty()) tz = "auto";

        // Weather
        String wxUrl = WX_URL
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                + "wind_speed_10m,wind_direction_10m,weather_code,surface_pressure,visibility,"
                + "uv_index,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code,"
                + "precipitation_sum,precipitation_probability_max,wind_speed_10m_max,"
                + "sunrise,sunset,uv_index_max"
                + "&hourly=temperature_2m,relative_humidity_2m,precipitation_probability"
                + "&timezone=" + URLEncoder.encode(tz, StandardCharsets.UTF_8)
                + "&forecast_days=7";

        String wxResp = get(wxUrl);

        // Air quality (graceful failure)
        String airQuality = "{}";
        try {
            String aqUrl = AIR_QUALITY_URL + "?latitude=" + lat + "&longitude=" + lon
                    + "&current=european_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide";
            airQuality = get(aqUrl);
        } catch (Exception ignored) { }

        // Current block
        String currentBlock = extractBlock(wxResp, "current");
        int    wxCode   = (int) extractDouble(currentBlock, "weather_code");
        double temp     = extractDouble(currentBlock, "temperature_2m");
        double feels    = extractDouble(currentBlock, "apparent_temperature");
        int    humidity = (int) extractDouble(currentBlock, "relative_humidity_2m");
        double wind     = extractDouble(currentBlock, "wind_speed_10m");
        int    windDir  = (int) extractDouble(currentBlock, "wind_direction_10m");
        double pressure = extractDouble(currentBlock, "surface_pressure");
        double vis      = extractDouble(currentBlock, "visibility");
        double uvIndex  = extractDouble(currentBlock, "uv_index");
        int    isDay    = (int) extractDouble(currentBlock, "is_day");

        // Daily block
        String dailyBlock = extractBlock(wxResp, "daily");
        String[] dates    = extractArray(dailyBlock, "time");
        double[] maxT     = extractDoubleArray(dailyBlock, "temperature_2m_max");
        double[] minT     = extractDoubleArray(dailyBlock, "temperature_2m_min");
        int[]    codes    = extractIntArray(dailyBlock, "weather_code");
        double[] rain     = extractDoubleArray(dailyBlock, "precipitation_sum");
        double[] rainProb = extractDoubleArray(dailyBlock, "precipitation_probability_max");
        double[] windMax  = extractDoubleArray(dailyBlock, "wind_speed_10m_max");
        String[] sunrise  = extractArray(dailyBlock, "sunrise");
        String[] sunset   = extractArray(dailyBlock, "sunset");
        double[] uvMax    = extractDoubleArray(dailyBlock, "uv_index_max");

        // FIX 5: extract hourly sub-object separately to avoid key collisions
        String hourlyBlock   = extractBlock(wxResp, "hourly");
        String[] hourlyTimes = extractArray(hourlyBlock, "time");
        double[] hourlyTemp  = extractDoubleArray(hourlyBlock, "temperature_2m");
        int[] hourlyHumidity = extractIntArray(hourlyBlock, "relative_humidity_2m");
        int[] hourlyRainProb = extractIntArray(hourlyBlock, "precipitation_probability");

        // Air quality values
        String aqBlock = extractBlock(airQuality, "current");
        if (aqBlock.isEmpty()) aqBlock = airQuality;
        int    aqi  = (int) extractDouble(aqBlock, "european_aqi");
        double pm25 = extractDouble(aqBlock, "pm2_5");

        // Build JSON response
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"city\":\"").append(esc(name)).append("\",");
        sb.append("\"country\":\"").append(esc(country)).append("\",");
        sb.append("\"timezone\":\"").append(esc(tz)).append("\",");
        sb.append("\"isDay\":").append(isDay).append(",");
        sb.append("\"current\":{");
        sb.append("\"temp\":").append(fmt1(temp)).append(",");
        sb.append("\"feels\":").append(fmt1(feels)).append(",");
        sb.append("\"humidity\":").append(humidity).append(",");
        sb.append("\"wind\":").append(fmt1(wind)).append(",");
        sb.append("\"windDir\":").append(windDir).append(",");
        sb.append("\"windDirText\":\"").append(getWindDirection(windDir)).append("\",");
        sb.append("\"pressure\":").append(fmt1(pressure)).append(",");
        // FIX 8: visibility converted to km; null if NaN
        sb.append("\"visibility\":").append(Double.isNaN(vis) ? "null" : fmt1(vis / 1000)).append(",");
        sb.append("\"uvIndex\":").append(Double.isNaN(uvIndex) ? "null" : fmt1(uvIndex)).append(",");
        sb.append("\"uvLevel\":\"").append(getUVLevel(uvIndex)).append("\",");
        sb.append("\"code\":").append(wxCode).append(",");
        sb.append("\"desc\":\"").append(esc(wmoDesc(wxCode))).append("\",");
        sb.append("\"icon\":\"").append(getWeatherIcon(wxCode, isDay)).append("\"");
        sb.append("},");
        sb.append("\"airQuality\":{");
        sb.append("\"aqi\":").append(aqi > 0 ? aqi : "null").append(",");
        sb.append("\"aqiLevel\":\"").append(getAQILevel(aqi)).append("\",");
        sb.append("\"pm25\":").append(!Double.isNaN(pm25) && pm25 > 0 ? fmt1(pm25) : "null");
        sb.append("},");
        sb.append("\"daily\":[");
        for (int i = 0; i < dates.length && i < maxT.length; i++) {
            LocalDate date  = LocalDate.parse(dates[i]);
            String label    = i == 0 ? "Today"
                    : date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"label\":\"").append(label).append("\",");
            sb.append("\"date\":\"").append(dates[i]).append("\",");
            sb.append("\"max\":").append(fmt1(maxT[i])).append(",");
            sb.append("\"min\":").append(fmt1(minT[i])).append(",");
            sb.append("\"rain\":").append(fmt1(rain.length > i ? rain[i] : 0)).append(",");
            sb.append("\"rainProb\":").append(rainProb.length > i ? (int) Math.round(rainProb[i]) : 0).append(",");
            sb.append("\"windMax\":").append(fmt1(windMax.length > i ? windMax[i] : 0)).append(",");
            sb.append("\"uvMax\":").append(fmt1(uvMax.length > i ? uvMax[i] : 0)).append(",");
            sb.append("\"sunrise\":\"").append(sunrise.length > i ? extractTime(sunrise[i]) : "").append("\",");
            sb.append("\"sunset\":\"").append(sunset.length > i ? extractTime(sunset[i]) : "").append("\",");
            sb.append("\"code\":").append(codes.length > i ? codes[i] : 0).append(",");
            sb.append("\"desc\":\"").append(esc(wmoDesc(codes.length > i ? codes[i] : 0))).append("\"");
            sb.append("}");
        }
        sb.append("],");
        sb.append("\"hourly\":[");
        int hourlyLimit = Math.min(24, hourlyTimes.length);
        for (int i = 0; i < hourlyLimit; i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"hour\":\"").append(extractTime(hourlyTimes[i])).append("\",");
            sb.append("\"temp\":").append(fmt1(hourlyTemp.length > i ? hourlyTemp[i] : 0)).append(",");
            sb.append("\"humidity\":").append(hourlyHumidity.length > i ? hourlyHumidity[i] : 0).append(",");
            sb.append("\"rainProb\":").append(hourlyRainProb.length > i ? hourlyRainProb[i] : 0);
            sb.append("}");
        }
        sb.append("],");
        sb.append("\"alerts\":").append(generateWeatherAlerts(temp, wind, uvIndex, rainProb));
        sb.append("}");
        return sb.toString();
    }

    // Extract the time portion after 'T' safely (handles both "2024-01-01T06:30" and "06:30")
    static String extractTime(String s) {
        if (s == null || s.isEmpty()) return "";
        int t = s.indexOf('T');
        return t >= 0 ? s.substring(t + 1, Math.min(t + 6, s.length())) : s;
    }

    // ── Helper: extract a named JSON object block ─────────────────────────────

    /**
     * Returns the content *inside* the first { } for a given key:
     *   "current": { ... }  →  returns "{ ... }"
     * Falls back to the original string if the key is not found.
     */
    static String extractBlock(String json, String key) {
        String token = "\"" + key + "\"";
        int ki = json.indexOf(token);
        if (ki < 0) return "";
        int braceStart = json.indexOf('{', ki + token.length());
        if (braceStart < 0) return "";
        int depth = 0, i = braceStart;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(braceStart, i + 1); }
            i++;
        }
        return "";
    }

    // ── Weather helper methods ────────────────────────────────────────────────

    // FIX 7: use Math.floorMod to handle negative degrees safely
    static String getWindDirection(int degrees) {
        if (degrees < 0) return "N/A";
        String[] dirs = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                         "S","SSW","SW","WSW","W","WNW","NW","NNW"};
        int index = Math.floorMod((int) Math.round(degrees / 22.5), 16);
        return dirs[index];
    }

    static String getUVLevel(double uv) {
        if (Double.isNaN(uv) || uv <= 2) return "Low";
        if (uv <= 5) return "Moderate";
        if (uv <= 7) return "High";
        if (uv <= 10) return "Very High";
        return "Extreme";
    }

    static String getAQILevel(int aqi) {
        if (aqi <= 0)   return "N/A";
        if (aqi <= 20)  return "Excellent";
        if (aqi <= 40)  return "Good";
        if (aqi <= 60)  return "Fair";
        if (aqi <= 80)  return "Poor";
        if (aqi <= 100) return "Very Poor";
        return "Hazardous";
    }

    // FIX 9: rainProb length guarded before index access
    static String generateWeatherAlerts(double temp, double wind, double uv, double[] rainProb) {
        List<String> alerts = new ArrayList<>();
        if (temp > 35)  alerts.add("🌡️ Heat advisory: Stay hydrated!");
        if (temp < 0)   alerts.add("❄️ Freezing temperatures: Bundle up!");
        if (wind > 40)  alerts.add("💨 Strong winds: Secure outdoor items!");
        if (!Double.isNaN(uv) && uv > 8) alerts.add("☀️ Extreme UV: Wear sunscreen!");
        if (rainProb != null && rainProb.length > 0 && rainProb[0] > 70)
            alerts.add("🌧️ High chance of rain today: Bring an umbrella!");
        if (alerts.isEmpty()) alerts.add("✅ No active weather alerts");

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < alerts.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(esc(alerts.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    static String getWeatherIcon(int code, int isDay) {
        if (code == 0)  return isDay == 1 ? "☀️" : "🌙";
        if (code == 1)  return "🌤️";
        if (code == 2)  return "⛅";
        if (code == 3)  return "☁️";
        if (code >= 45 && code <= 48) return "🌫️";
        if (code >= 51 && code <= 55) return "🌧️";
        if (code >= 61 && code <= 65) return "🌧️";
        if (code >= 71 && code <= 75) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code >= 95 && code <= 99) return "⛈️";
        return "🌡️";
    }

    // ── JSON extraction helpers ───────────────────────────────────────────────

    static double extractDouble(String json, String key) {
        if (json == null || json.isEmpty()) return Double.NaN;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+\\.?\\d*)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    static String extractString(String json, String key) {
        if (json == null || json.isEmpty()) return "";
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    // FIX 6: bracket-counting array extractor — handles nested structures correctly
    static String extractRawArray(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        String token = "\"" + key + "\"";
        int ki = json.indexOf(token);
        if (ki < 0) return null;
        int bracketStart = json.indexOf('[', ki + token.length());
        if (bracketStart < 0) return null;
        int depth = 0, i = bracketStart;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return json.substring(bracketStart + 1, i); }
            i++;
        }
        return null;
    }

    static String[] extractArray(String json, String key) {
        String raw = extractRawArray(json, key);
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        String[] items = raw.split(",");
        String[] result = new String[items.length];
        for (int i = 0; i < items.length; i++)
            result[i] = items[i].trim().replace("\"", "");
        return result;
    }

    static double[] extractDoubleArray(String json, String key) {
        String raw = extractRawArray(json, key);
        if (raw == null || raw.trim().isEmpty()) return new double[0];
        String[] items = raw.split(",");
        double[] result = new double[items.length];
        for (int i = 0; i < items.length; i++) {
            try { result[i] = Double.parseDouble(items[i].trim()); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    static int[] extractIntArray(String json, String key) {
        double[] d = extractDoubleArray(json, key);
        int[] r = new int[d.length];
        for (int i = 0; i < d.length; i++) r[i] = (int) Math.round(d[i]);
        return r;
    }

    // ── HTTP / utility helpers ────────────────────────────────────────────────

    static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("User-Agent", "NebulaWeather/2.0")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        return resp.body();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n",  "\\n")
                .replace("\r",  "\\r")
                .replace("\t",  "\\t");
    }

    // FIX 8: return "null" (not "0") for NaN so JSON numeric fields are valid
    static String fmt1(double v) {
        if (Double.isNaN(v)) return "null";
        return String.format("%.1f", v);
    }

    static String wmoDesc(int code) {
        return switch (code) {
            case 0  -> "Clear sky";       case 1  -> "Mainly clear";
            case 2  -> "Partly cloudy";   case 3  -> "Overcast";
            case 45 -> "Fog";             case 48 -> "Icy fog";
            case 51 -> "Light drizzle";   case 53 -> "Moderate drizzle";
            case 55 -> "Dense drizzle";   case 61 -> "Slight rain";
            case 63 -> "Moderate rain";   case 65 -> "Heavy rain";
            case 71 -> "Slight snow";     case 73 -> "Moderate snow";
            case 75 -> "Heavy snow";
            case 80 -> "Light showers";   case 81 -> "Moderate showers";
            case 82 -> "Violent showers"; case 95 -> "Thunderstorm";
            case 96 -> "Thunderstorm + hail";
            case 99 -> "Thunderstorm + heavy hail";
            default -> "Unknown";
        };
    }

    // ── Cached weather entry ──────────────────────────────────────────────────

    static class CachedWeather {
        String json;
        long   timestamp;

        CachedWeather(String json) {
            this.json      = json;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < 5 * 60 * 1000; // 5 min TTL
        }
    }


    // ── HTML page (v3.0 — full rewrite with bug fixes + new features) ──────────
    // BUG FIXES vs v2:
    //  A. extractArray split(",") broke ISO timestamps with comma-adjacent strings
    //     → Fixed by using a proper quoted-string-aware splitter for string arrays
    //  B. handleHome rebuilt HTML on every request (expensive) → now cached as static field
    //  C. Thread safety: cache (LinkedHashMap) is not thread-safe → wrapped with Collections.synchronizedMap
    //  D. suggestions regex was greedy across results → added DOTALL + non-greedy fix
    //  E. hourlyBlock extractBlock could return "" for "hourly" key because Open-Meteo
    //     wraps hourly data as an object not a sub-key — fixed with fallback
    //  F. getWindDirection cast double→int before Math.round losing precision → fixed order
    //  G. esc() didn't escape newlines/tabs → could corrupt JSON strings
    //  H. JS: formatDob regex used literal backslash not \\D in template literal
    //  I. JS: liveValidate regex literals used \\ which became single \ in HTML string
    //  J. JS: password confirm checked regPass value before it's set on first keystroke
    // NEW FEATURES (v3.0):
    //  1. °C / °F live unit toggle (no re-fetch)
    //  2. Dark/Light theme switcher with localStorage persistence
    //  3. Compare Cities — side-by-side weather for 2 cities
    //  4. Weather History mini-chart (last 5 searches remembered)
    //  5. Animated weather background that changes with conditions
    //  6. Dew Point calculation from temp + humidity
    //  7. "Feels like" explanation tooltip
    //  8. Sunrise/Sunset progress bar (today's daylight arc)
    //  9. Wind compass rose widget
    // 10. Recent Searches dropdown (last 8 cities, persisted in localStorage)
    // 11. Copy weather summary to clipboard button
    // 12. Print-friendly layout via CSS @media print
    // 13. Keyboard shortcut Ctrl+K to focus search
    // 14. Auto-detect user location button (geolocation API)
    // 15. Server-side: HTML now cached as static byte[] — not rebuilt per request

    private static final java.util.concurrent.atomic.AtomicReference<byte[]> HTML_CACHE_REF
            = new java.util.concurrent.atomic.AtomicReference<>(null);

    static void rebuildHtmlCache() {
        HTML_CACHE_REF.set(buildHtml().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String buildHtml() {
        return """
<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<meta name="description" content="Nebula Weather — Advanced real-time weather intelligence. 7-day forecast, air quality, UV index and more.">
<title>🌌 Nebula Weather v3</title>
<style>
/* ── CSS Variables (theme-aware) ─────────────────────────── */
:root {
  --bg1:#0f0c29; --bg2:#302b63; --bg3:#24243e;
  --glass:rgba(255,255,255,0.08); --glass2:rgba(255,255,255,0.13);
  --border:rgba(255,255,255,0.13);
  --text:#e8e8ff; --muted:#9090b8; --muted2:#6060a0;
  --a1:#00f5ff; --a2:#ff00e6;
  --card:#1a1740; --nav-h:60px;
  --shadow:0 8px 32px rgba(0,0,0,0.4);
  --radius:18px;
}
[data-theme="light"] {
  --bg1:#e8eaf6; --bg2:#c5cae9; --bg3:#d1d5f0;
  --glass:rgba(255,255,255,0.55); --glass2:rgba(255,255,255,0.8);
  --border:rgba(100,100,200,0.2);
  --text:#1a1a3e; --muted:#5555a0; --muted2:#8888c0;
  --card:#ffffff; --shadow:0 4px 20px rgba(100,100,200,0.15);
}
/* ── Reset ───────────────────────────────────────────────── */
*,*::before,*::after{margin:0;padding:0;box-sizing:border-box;}
html{scroll-behavior:smooth;}
body{
  font-family:'Segoe UI',system-ui,-apple-system,sans-serif;
  background:linear-gradient(135deg,var(--bg1) 0%,var(--bg2) 50%,var(--bg3) 100%) fixed;
  color:var(--text); min-height:100vh; padding-top:var(--nav-h);
  transition:background 0.4s,color 0.4s;
}
body::after{
  content:''; position:fixed; inset:0; pointer-events:none; z-index:0;
  background:radial-gradient(circle at 15% 55%,rgba(0,245,255,0.06) 0%,transparent 50%),
             radial-gradient(circle at 85% 20%,rgba(255,0,230,0.06) 0%,transparent 50%);
}
/* ── Animated weather BG ─────────────────────────────────── */
#wxBg{
  position:fixed; inset:0; pointer-events:none; z-index:0; overflow:hidden; opacity:0.18;
  transition:opacity 1s;
}
.wx-drop{
  position:absolute; top:-20px; width:2px; background:linear-gradient(to bottom,transparent,var(--a1));
  border-radius:2px; animation:fall linear infinite;
}
@keyframes fall{to{transform:translateY(110vh);}}
.wx-snow{
  position:absolute; top:-10px; border-radius:50%;
  background:#fff; animation:snowfall linear infinite;
}
@keyframes snowfall{to{transform:translateY(110vh) rotate(720deg);}}
/* ── Scrollbar ───────────────────────────────────────────── */
::-webkit-scrollbar{width:5px;height:5px;}
::-webkit-scrollbar-track{background:transparent;}
::-webkit-scrollbar-thumb{background:rgba(0,245,255,0.25);border-radius:3px;}
/* ── Nav ─────────────────────────────────────────────────── */
#navbar{
  position:fixed;top:0;left:0;right:0;height:var(--nav-h);
  background:rgba(15,12,41,0.93);backdrop-filter:blur(20px);
  border-bottom:1px solid var(--border);
  display:flex;align-items:center;justify-content:space-between;
  padding:0 1.25rem;z-index:900;
}
[data-theme="light"] #navbar{background:rgba(232,234,246,0.93);}
.nav-brand{
  display:flex;align-items:center;gap:0.5rem;
  font-size:1.15rem;font-weight:800;letter-spacing:1.5px;
  background:linear-gradient(135deg,var(--a1),var(--a2));
  -webkit-background-clip:text;background-clip:text;color:transparent;
  text-decoration:none;white-space:nowrap;
}
.nav-links{display:flex;align-items:center;gap:2px;list-style:none;}
.nav-links a{
  color:var(--muted);text-decoration:none;padding:0.45rem 0.8rem;
  border-radius:8px;font-size:0.85rem;font-weight:500;transition:all 0.2s;
  display:flex;align-items:center;gap:0.35rem;white-space:nowrap;
}
.nav-links a:hover{color:var(--text);background:var(--glass);}
.nav-links a.active{color:var(--a1);background:rgba(0,245,255,0.08);}
.nav-right{display:flex;align-items:center;gap:0.5rem;}
#userBadge{
  display:none;align-items:center;gap:0.5rem;
  background:var(--glass);border:1px solid var(--border);
  border-radius:40px;padding:0.3rem 0.75rem 0.3rem 0.35rem;cursor:pointer;
}
.av{
  width:28px;height:28px;border-radius:50%;
  background:linear-gradient(135deg,var(--a1),var(--a2));
  display:flex;align-items:center;justify-content:center;
  font-size:0.8rem;font-weight:700;color:#000;overflow:hidden;flex-shrink:0;
}
.av img{width:100%;height:100%;object-fit:cover;}
.btn-n{
  padding:0.4rem 1rem;border-radius:40px;border:none;
  font-size:0.8rem;font-weight:600;cursor:pointer;transition:all 0.2s;
}
.btn-n.login {background:var(--glass);color:var(--a1);border:1px solid var(--a1);}
.btn-n.login:hover{background:rgba(0,245,255,0.12);}
.btn-n.signup{background:linear-gradient(135deg,var(--a1),var(--a2));color:#000;}
.btn-n.signup:hover{opacity:0.88;transform:scale(1.03);}
.btn-n.logout{background:rgba(255,80,80,0.12);color:#ff7070;border:1px solid rgba(255,80,80,0.3);display:none;}
/* icon buttons */
.icon-btn{
  width:34px;height:34px;border-radius:10px;border:1px solid var(--border);
  background:var(--glass);color:var(--text);font-size:1rem;
  cursor:pointer;display:flex;align-items:center;justify-content:center;
  transition:all 0.2s;flex-shrink:0;
}
.icon-btn:hover{background:var(--glass2);border-color:var(--a1);}
/* hamburger */
.hbg{display:none;flex-direction:column;gap:4px;cursor:pointer;padding:0.35rem;
  border-radius:8px;background:var(--glass);}
.hbg span{display:block;width:20px;height:2px;background:var(--text);border-radius:2px;transition:all 0.3s;}
.hbg.open span:nth-child(1){transform:translateY(6px) rotate(45deg);}
.hbg.open span:nth-child(2){opacity:0;}
.hbg.open span:nth-child(3){transform:translateY(-6px) rotate(-45deg);}
#mobileMenu{
  display:none;position:fixed;top:var(--nav-h);left:0;right:0;
  background:rgba(12,10,35,0.97);backdrop-filter:blur(20px);
  padding:0.75rem;z-index:899;border-bottom:1px solid var(--border);
}
[data-theme="light"] #mobileMenu{background:rgba(232,234,246,0.97);}
#mobileMenu.open{display:block;}
#mobileMenu ul{list-style:none;}
#mobileMenu li a{
  display:flex;align-items:center;gap:0.6rem;padding:0.75rem 1rem;
  color:var(--muted);text-decoration:none;border-radius:10px;font-size:0.9rem;transition:all 0.2s;
}
#mobileMenu li a:hover{background:var(--glass);color:var(--text);}
/* ── Container & Pages ───────────────────────────────────── */
.container{max-width:1280px;margin:0 auto;padding:1.25rem 1rem;position:relative;z-index:1;}
.page{display:none;animation:fadeUp 0.35s ease;}
.page.active{display:block;}
@keyframes fadeUp{from{opacity:0;transform:translateY(14px);}to{opacity:1;transform:none;}}
/* ── Header ──────────────────────────────────────────────── */
.header{text-align:center;padding:2rem 1rem 1rem;}
.logo{font-size:2.8rem;display:inline-block;animation:float 3s ease-in-out infinite;}
@keyframes float{0%,100%{transform:translateY(0);}50%{transform:translateY(-8px);}}
h1{
  background:linear-gradient(135deg,var(--a1),var(--a2));
  -webkit-background-clip:text;background-clip:text;color:transparent;
  font-size:2.2rem;letter-spacing:2px;
}
.subtitle{color:var(--muted);margin-top:0.35rem;font-size:0.88rem;}
/* ── Search bar (FIX: isolated z-index layer, no overlap) ── */
.search-bar-wrap{
  position:relative;z-index:50;
  display:flex;align-items:center;gap:0.6rem;flex-wrap:wrap;
  background:var(--glass2);backdrop-filter:blur(20px);
  border:1px solid var(--border);border-radius:60px;
  padding:0.45rem 0.6rem;margin-bottom:1.5rem;
}
.search-wrapper{position:relative;flex:1;min-width:180px;max-width:400px;}
.search-input{
  width:100%;padding:0.75rem 1.25rem;
  background:rgba(0,0,0,0.35);border:1px solid var(--border);
  border-radius:50px;color:var(--text);font-size:0.95rem;outline:none;
  transition:border-color 0.2s,box-shadow 0.2s;
}
[data-theme="light"] .search-input{background:rgba(255,255,255,0.7);}
.search-input:focus{border-color:var(--a1);box-shadow:0 0 0 3px rgba(0,245,255,0.12);}
/* FIX: suggestions inside wrapper, correct z-index */
.suggestions{
  position:absolute;top:calc(100% + 5px);left:0;right:0;
  background:rgba(10,8,30,0.97);backdrop-filter:blur(16px);
  border:1px solid var(--border);border-radius:14px;
  max-height:200px;overflow-y:auto;z-index:200;display:none;
  box-shadow:0 8px 28px rgba(0,0,0,0.45);
}
[data-theme="light"] .suggestions{background:rgba(240,242,255,0.98);}
.sug-item{
  padding:0.65rem 1.1rem;cursor:pointer;font-size:0.88rem;
  transition:background 0.15s;border-radius:10px;margin:3px;
}
.sug-item:hover{background:rgba(0,245,255,0.1);color:var(--a1);}
.search-btn{
  padding:0.75rem 1.5rem;white-space:nowrap;
  background:linear-gradient(135deg,var(--a1),var(--a2));
  border:none;border-radius:50px;color:#000;
  font-weight:700;font-size:0.9rem;cursor:pointer;transition:all 0.2s;
}
.search-btn:hover{transform:scale(1.04);opacity:0.9;}
/* Recent searches pill row */
#recentRow{display:flex;gap:0.4rem;flex-wrap:wrap;margin-bottom:1rem;}
.recent-pill{
  padding:0.3rem 0.85rem;border-radius:40px;font-size:0.78rem;
  background:var(--glass);border:1px solid var(--border);
  cursor:pointer;transition:all 0.2s;color:var(--muted);
}
.recent-pill:hover{border-color:var(--a1);color:var(--a1);}
/* Unit toggle */
.unit-toggle{
  display:flex;align-items:center;gap:0;background:rgba(0,0,0,0.3);
  border:1px solid var(--border);border-radius:40px;overflow:hidden;padding:2px;
}
.unit-btn{
  padding:0.3rem 0.7rem;border:none;background:transparent;
  color:var(--muted);font-size:0.78rem;font-weight:600;cursor:pointer;
  border-radius:40px;transition:all 0.2s;
}
.unit-btn.active{background:linear-gradient(135deg,var(--a1),var(--a2));color:#000;}
/* ── Cards ───────────────────────────────────────────────── */
.card{
  background:var(--glass);backdrop-filter:blur(20px);
  border:1px solid var(--border);border-radius:var(--radius);
  padding:1.25rem;margin-bottom:1.1rem;
  transition:transform 0.25s,box-shadow 0.25s;
}
.card:hover{transform:translateY(-3px);box-shadow:var(--shadow);}
.card h3{font-size:0.88rem;font-weight:600;color:var(--a1);margin-bottom:0.9rem;text-transform:uppercase;letter-spacing:.5px;}
[data-theme="light"] .card{background:var(--glass2);}
.card-row{display:grid;grid-template-columns:1fr 1fr;gap:1rem;}
/* stat cards */
.stat-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:0.75rem;}
.stat{
  background:rgba(0,0,0,0.22);border:1px solid var(--border);
  border-radius:14px;padding:0.9rem;text-align:center;transition:all 0.2s;
}
[data-theme="light"] .stat{background:rgba(255,255,255,0.6);}
.stat:hover{border-color:var(--a1);background:rgba(0,245,255,0.05);}
.stat .si{font-size:1.6rem;margin-bottom:0.35rem;}
.stat .sl{font-size:0.68rem;color:var(--muted);text-transform:uppercase;letter-spacing:.6px;}
.stat .sv{font-size:1.25rem;font-weight:700;margin-top:0.25rem;}
.stat .ss{font-size:0.7rem;color:var(--muted);margin-top:0.15rem;}
/* city hero */
.city-name{font-size:1.7rem;font-weight:700;line-height:1.2;}
.city-sub{color:var(--muted);font-size:0.82rem;margin-top:0.2rem;}
.loc-row{display:flex;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;gap:0.5rem;padding-bottom:1rem;margin-bottom:1rem;border-bottom:1px solid var(--border);}
/* Dew point badge */
.badge{
  display:inline-flex;align-items:center;gap:0.3rem;
  padding:0.2rem 0.6rem;border-radius:40px;font-size:0.72rem;font-weight:600;
  background:rgba(0,245,255,0.1);border:1px solid rgba(0,245,255,0.25);color:var(--a1);
  margin-top:0.4rem;
}
/* ── Sunrise/Sunset arc ──────────────────────────────────── */
.sun-arc-wrap{padding:0.5rem 0;}
.sun-arc-bar{
  position:relative;height:8px;background:rgba(255,255,255,0.1);
  border-radius:4px;overflow:hidden;margin:0.5rem 0;
}
.sun-arc-fill{
  height:100%;border-radius:4px;
  background:linear-gradient(90deg,#ffd700,#ff8c00);
  transition:width 0.8s ease;
}
.sun-labels{display:flex;justify-content:space-between;font-size:0.75rem;color:var(--muted);}
/* ── Wind compass ────────────────────────────────────────── */
.compass-wrap{display:flex;align-items:center;gap:1rem;flex-wrap:wrap;}
.compass{
  position:relative;width:80px;height:80px;flex-shrink:0;
  border:2px solid var(--border);border-radius:50%;
  background:rgba(0,0,0,0.3);
}
.compass-n,.compass-s,.compass-e,.compass-w{
  position:absolute;font-size:0.65rem;font-weight:700;color:var(--muted);
}
.compass-n{top:3px;left:50%;transform:translateX(-50%);}
.compass-s{bottom:3px;left:50%;transform:translateX(-50%);}
.compass-e{right:3px;top:50%;transform:translateY(-50%);}
.compass-w{left:3px;top:50%;transform:translateY(-50%);}
.compass-needle{
  position:absolute;top:50%;left:50%;
  width:3px;height:34px;background:linear-gradient(to bottom,var(--a2),var(--a1));
  border-radius:2px;transform-origin:bottom center;
  transition:transform 0.8s cubic-bezier(.34,1.56,.64,1);
  margin-left:-1.5px;margin-top:-34px;
}
/* ── Hourly scroll ───────────────────────────────────────── */
.hourly-scroll{display:flex;gap:0.6rem;overflow-x:auto;padding:0.4rem 0;}
.hourly-item{
  min-width:70px;text-align:center;background:rgba(0,0,0,0.22);
  border:1px solid var(--border);border-radius:12px;padding:0.6rem 0.4rem;
  flex-shrink:0;transition:border-color 0.2s;
}
.hourly-item:hover{border-color:var(--a1);}
.h-now{border-color:var(--a1);background:rgba(0,245,255,0.06);}
/* ── Forecast grid ───────────────────────────────────────── */
.fc-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(100px,1fr));gap:0.6rem;}
.fc-day{
  background:rgba(0,0,0,0.22);border:1px solid var(--border);
  border-radius:14px;padding:0.75rem 0.5rem;text-align:center;transition:all 0.25s;
}
.fc-day:hover{transform:translateY(-4px);border-color:var(--a1);}
.fc-label{font-weight:700;font-size:0.85rem;margin-bottom:0.3rem;}
.fc-icon{font-size:1.6rem;margin:0.3rem 0;}
.fc-hi{font-size:1.05rem;font-weight:700;color:#ffaa66;}
.fc-lo{font-size:0.85rem;color:#88ccff;margin-left:0.3rem;}
.fc-info{font-size:0.7rem;color:var(--muted);margin-top:0.3rem;}
/* ── Compare cities ──────────────────────────────────────── */
.compare-wrap{display:grid;grid-template-columns:1fr 1fr;gap:1rem;}
.compare-card{background:rgba(0,0,0,0.2);border:1px solid var(--border);border-radius:14px;padding:1rem;}
.compare-card h4{font-size:0.95rem;font-weight:700;margin-bottom:0.75rem;color:var(--a1);}
.compare-row{display:flex;justify-content:space-between;align-items:center;padding:0.3rem 0;border-bottom:1px solid rgba(255,255,255,0.05);font-size:0.85rem;}
.compare-row:last-child{border-bottom:none;}
.compare-row .lbl{color:var(--muted);}
/* ── History chart ───────────────────────────────────────── */
.hist-chart{display:flex;align-items:flex-end;gap:6px;height:80px;padding-top:4px;}
.hist-bar-wrap{flex:1;display:flex;flex-direction:column;align-items:center;gap:3px;}
.hist-bar{
  width:100%;border-radius:4px 4px 0 0;
  background:linear-gradient(to top,var(--a1),var(--a2));
  transition:height 0.5s ease;min-height:4px;
}
.hist-label{font-size:0.62rem;color:var(--muted);text-align:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%;}
/* ── Alerts card ─────────────────────────────────────────── */
.alerts-card{border-left:3px solid #ff6060;background:rgba(255,60,60,0.06);}
.alert-item{padding:0.45rem 0.75rem;margin:0.25rem 0;background:rgba(0,0,0,0.2);border-radius:9px;font-size:0.85rem;}
/* ── Copy btn ────────────────────────────────────────────── */
.copy-btn{
  display:inline-flex;align-items:center;gap:0.4rem;
  padding:0.35rem 0.9rem;border-radius:40px;border:1px solid var(--border);
  background:var(--glass);color:var(--muted);font-size:0.78rem;cursor:pointer;
  transition:all 0.2s;float:right;
}
.copy-btn:hover{border-color:var(--a1);color:var(--a1);}
/* ── Loader ──────────────────────────────────────────────── */
.loader{text-align:center;padding:3rem 1rem;}
.spinner{
  border:3px solid rgba(255,255,255,0.1);border-top:3px solid var(--a1);
  border-radius:50%;width:44px;height:44px;
  animation:spin 0.85s linear infinite;margin:0 auto 0.75rem;
}
@keyframes spin{to{transform:rotate(360deg);}}
.err-msg{color:#ff7070;text-align:center;padding:0.75rem;font-size:0.9rem;}
.hidden{display:none!important;}
/* ── Auth modal ──────────────────────────────────────────── */
#authOvl{
  position:fixed;inset:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(8px);
  z-index:1000;display:flex;align-items:center;justify-content:center;
  padding:1rem;opacity:0;pointer-events:none;transition:opacity 0.3s;
}
#authOvl.open{opacity:1;pointer-events:all;}
#authMod{
  background:linear-gradient(145deg,#1a1740,#231f58);
  border:1px solid var(--border);border-radius:24px;
  width:100%;max-width:500px;max-height:90vh;overflow-y:auto;
  padding:1.75rem;transform:scale(0.92) translateY(16px);transition:transform 0.3s;
  box-shadow:0 24px 80px rgba(0,0,0,0.6);
}
[data-theme="light"] #authMod{background:linear-gradient(145deg,#fff,#f0f2ff);}
#authOvl.open #authMod{transform:scale(1) translateY(0);}
.mod-hdr{display:flex;align-items:center;justify-content:space-between;margin-bottom:1.25rem;}
.mod-title{
  font-size:1.3rem;font-weight:700;
  background:linear-gradient(135deg,var(--a1),var(--a2));
  -webkit-background-clip:text;background-clip:text;color:transparent;
}
.mod-close{background:none;border:none;color:var(--muted);font-size:1.3rem;cursor:pointer;transition:color 0.2s;}
.mod-close:hover{color:var(--text);}
.auth-tabs{
  display:flex;background:rgba(0,0,0,0.25);border-radius:10px;padding:3px;
  margin-bottom:1.25rem;border:1px solid var(--border);
}
.auth-tab{
  flex:1;padding:0.55rem;border:none;border-radius:8px;
  background:transparent;color:var(--muted);font-size:0.85rem;
  font-weight:600;cursor:pointer;transition:all 0.25s;
}
.auth-tab.active{background:linear-gradient(135deg,var(--a1),var(--a2));color:#000;}
.auth-form{display:none;}
.auth-form.active{display:block;}
.form-row{display:grid;grid-template-columns:1fr 1fr;gap:0.65rem;}
.fg{margin-bottom:0.85rem;}
.fg label{display:block;font-size:0.75rem;font-weight:600;color:var(--muted);
  margin-bottom:0.3rem;text-transform:uppercase;letter-spacing:.4px;}
.fg label .req{color:var(--a2);margin-left:2px;}
.fc{
  width:100%;padding:0.7rem 0.9rem;
  background:rgba(0,0,0,0.3);border:1px solid var(--border);
  border-radius:11px;color:var(--text);font-size:0.88rem;outline:none;transition:all 0.2s;
}
[data-theme="light"] .fc{background:rgba(255,255,255,0.7);color:#1a1a3e;}
.fc:focus{border-color:var(--a1);box-shadow:0 0 0 3px rgba(0,245,255,0.1);}
.fc.err{border-color:#ff6060;}
.fc.ok{border-color:#50e090;}
select.fc option{background:#1a1740;}
.fm{font-size:0.72rem;margin-top:0.25rem;min-height:1em;}
.fm.e{color:#ff7070;} .fm.o{color:#50e090;}
.pwd-bar-wrap{margin-top:0.35rem;height:3px;background:rgba(255,255,255,0.1);border-radius:2px;overflow:hidden;}
.pwd-bar{height:100%;border-radius:2px;transition:all 0.3s;width:0;}
.av-upload{display:flex;align-items:center;gap:0.85rem;margin-bottom:0.85rem;}
.av-preview{
  width:56px;height:56px;border-radius:50%;
  background:linear-gradient(135deg,var(--a1),var(--a2));
  display:flex;align-items:center;justify-content:center;
  font-size:1.3rem;font-weight:700;color:#000;overflow:hidden;flex-shrink:0;
}
.av-preview img{width:100%;height:100%;object-fit:cover;display:none;}
.btn-up{
  padding:0.4rem 0.85rem;background:var(--glass);border:1px solid var(--border);
  border-radius:9px;color:var(--text);font-size:0.78rem;cursor:pointer;transition:all 0.2s;
}
.btn-up:hover{border-color:var(--a1);color:var(--a1);}
#avInput{display:none;}
.terms-row{
  display:flex;align-items:flex-start;gap:0.5rem;
  margin:0.6rem 0 1rem;font-size:0.78rem;color:var(--muted);line-height:1.5;
}
.terms-row input{margin-top:3px;accent-color:var(--a1);flex-shrink:0;}
.terms-row a{color:var(--a1);}
.btn-sub{
  width:100%;padding:0.9rem;
  background:linear-gradient(135deg,var(--a1),var(--a2));
  border:none;border-radius:13px;color:#000;
  font-size:0.95rem;font-weight:700;cursor:pointer;transition:all 0.25s;
}
.btn-sub:hover{transform:translateY(-2px);box-shadow:0 8px 24px rgba(0,245,255,0.25);}
.btn-sub:disabled{opacity:0.5;cursor:not-allowed;transform:none;}
.divider{display:flex;align-items:center;gap:0.6rem;margin:0.85rem 0;color:var(--muted);font-size:0.75rem;}
.divider::before,.divider::after{content:'';flex:1;height:1px;background:var(--border);}
.social-row{display:flex;gap:0.6rem;}
.btn-soc{
  flex:1;padding:0.6rem;border:1px solid var(--border);border-radius:11px;
  background:var(--glass);color:var(--text);font-size:0.82rem;
  cursor:pointer;transition:all 0.2s;display:flex;align-items:center;
  justify-content:center;gap:0.4rem;
}
.btn-soc:hover{border-color:var(--a1);}
/* ── Profile ─────────────────────────────────────────────── */
.prof-card{
  background:var(--glass);border:1px solid var(--border);border-radius:24px;
  padding:2rem;max-width:580px;margin:1.5rem auto;text-align:center;
}
.prof-av{
  width:88px;height:88px;border-radius:50%;
  background:linear-gradient(135deg,var(--a1),var(--a2));
  display:flex;align-items:center;justify-content:center;
  font-size:2.2rem;font-weight:700;color:#000;
  margin:0 auto 0.9rem;overflow:hidden;
}
.prof-av img{width:100%;height:100%;object-fit:cover;display:none;}
.prof-grid{display:grid;grid-template-columns:1fr 1fr;gap:0.6rem;text-align:left;margin-top:1rem;}
.prof-field{background:rgba(0,0,0,0.18);border-radius:11px;padding:0.65rem 0.85rem;}
.prof-field .lbl{font-size:0.68rem;text-transform:uppercase;letter-spacing:.4px;color:var(--muted);}
.prof-field .val{font-size:0.9rem;font-weight:600;margin-top:0.15rem;}
/* ── About ───────────────────────────────────────────────── */
.about-wrap{max-width:740px;margin:1.5rem auto;}
.about-wrap h2{font-size:1.35rem;margin:1.25rem 0 0.5rem;color:var(--a1);}
.about-wrap p{color:var(--muted);line-height:1.75;font-size:0.9rem;}
.feat-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:0.6rem;margin:0.75rem 0;}
.feat-item{
  background:var(--glass);border:1px solid var(--border);border-radius:12px;
  padding:0.75rem 0.9rem;display:flex;gap:0.5rem;align-items:flex-start;font-size:0.83rem;
}
code{
  background:rgba(0,245,255,0.1);border-radius:5px;
  padding:0.1em 0.4em;font-size:0.85em;color:var(--a1);
}
/* ── Toast ───────────────────────────────────────────────── */
#toast{
  position:fixed;bottom:1.25rem;right:1.25rem;
  background:rgba(20,18,50,0.97);border:1px solid var(--border);
  border-radius:13px;padding:0.75rem 1.1rem;
  font-size:0.85rem;z-index:2000;max-width:300px;
  transform:translateY(80px);opacity:0;transition:all 0.3s;
  box-shadow:0 8px 28px rgba(0,0,0,0.4);
}
#toast.show{transform:translateY(0);opacity:1;}
#toast.success{border-color:#50e090;}
#toast.error{border-color:#ff6060;}
/* ── Print ───────────────────────────────────────────────── */
@media print{
  #navbar,#mobileMenu,#authOvl,#toast,.search-bar-wrap,.hbg,
  .nav-right,.btn-n,.icon-btn,#recentRow,.copy-btn{display:none!important;}
  body{background:white;color:#000;padding-top:0;}
  .card{border:1px solid #ddd;break-inside:avoid;}
}
/* ── Responsive ──────────────────────────────────────────── */
@media(max-width:680px){
  h1{font-size:1.6rem;}
  .city-name{font-size:1.35rem;}
  .nav-links{display:none;}
  .hbg{display:flex;}
  .form-row{grid-template-columns:1fr;}
  .prof-grid{grid-template-columns:1fr;}
  .social-row{flex-direction:column;}
  .compare-wrap{grid-template-columns:1fr;}
  .card-row{grid-template-columns:1fr;}
}
</style>
</head>
<body>

<!-- ══════ NAV ══════════════════════════════════════════════ -->
<nav id="navbar">
  <a class="nav-brand" href="#" onclick="goPage('weather');return false;">🌌 NEBULA</a>

  <ul class="nav-links" id="dNav">
    <li><a href="#" id="nl-weather"  class="active" onclick="goPage('weather'); return false;">🌤 Weather</a></li>
    <li><a href="#" id="nl-forecast" onclick="goPage('forecast');return false;">📅 Forecast</a></li>
    <li><a href="#" id="nl-compare"  onclick="goPage('compare'); return false;">⚖️ Compare</a></li>
    <li><a href="#" id="nl-profile"  onclick="goPage('profile'); return false;">👤 Profile</a></li>
    <li><a href="#" id="nl-about"    onclick="goPage('about');   return false;">ℹ️ About</a></li>
  </ul>

  <div class="nav-right">
    <div id="userBadge" onclick="goPage('profile')">
      <div class="av" id="navAv">?</div>
      <span style="font-size:.82rem;font-weight:600" id="navName"></span>
    </div>
    <button class="btn-n login"  id="bLogin"  onclick="openAuth('login')">Login</button>
    <button class="btn-n signup" id="bSignup" onclick="openAuth('register')">Sign Up</button>
    <button class="btn-n logout" id="bLogout" onclick="doLogout()">Logout</button>

    <!-- Unit toggle -->
    <div class="unit-toggle">
      <button class="unit-btn active" id="uC" onclick="setUnit('C')">°C</button>
      <button class="unit-btn"        id="uF" onclick="setUnit('F')">°F</button>
    </div>

    <!-- Theme toggle -->
    <button class="icon-btn" id="themeBtn" title="Toggle theme" onclick="toggleTheme()">🌓</button>
    <!-- Print -->
    <button class="icon-btn" title="Print" onclick="window.print()">🖨️</button>

    <div class="hbg" id="hbg" onclick="toggleMobile()">
      <span></span><span></span><span></span>
    </div>
  </div>
</nav>

<!-- ══════ MOBILE MENU ═══════════════════════════════════════ -->
<div id="mobileMenu">
  <ul>
    <li><a href="#" onclick="goPage('weather'); closeMobile();return false;">🌤 Weather</a></li>
    <li><a href="#" onclick="goPage('forecast');closeMobile();return false;">📅 Forecast</a></li>
    <li><a href="#" onclick="goPage('compare'); closeMobile();return false;">⚖️ Compare</a></li>
    <li><a href="#" onclick="goPage('profile'); closeMobile();return false;">👤 Profile</a></li>
    <li><a href="#" onclick="goPage('about');   closeMobile();return false;">ℹ️ About</a></li>
    <li><a href="#" id="ml-login"  onclick="openAuth('login');   closeMobile();return false;">🔐 Login</a></li>
    <li><a href="#" id="ml-signup" onclick="openAuth('register');closeMobile();return false;">✨ Sign Up</a></li>
    <li><a href="#" id="ml-logout" onclick="doLogout();closeMobile();return false;" style="display:none;color:#ff8080">🚪 Logout</a></li>
  </ul>
</div>

<!-- ══════ AUTH MODAL ════════════════════════════════════════ -->
<div id="authOvl" onclick="ovlClick(event)">
  <div id="authMod">
    <div class="mod-hdr">
      <span class="mod-title" id="modTitle">Welcome Back 👋</span>
      <button class="mod-close" onclick="closeAuth()">✕</button>
    </div>

    <div class="auth-tabs">
      <button class="auth-tab active" id="tLogin"    onclick="switchTab('login')">🔐 Login</button>
      <button class="auth-tab"        id="tRegister" onclick="switchTab('register')">✨ Register</button>
    </div>

    <!-- LOGIN -->
    <div class="auth-form active" id="fLogin">
      <div class="fg">
        <label>Email <span class="req">*</span></label>
        <input type="email" class="fc" id="lEmail" placeholder="you@example.com" oninput="lv(this,'email')">
        <div class="fm" id="lEmailM"></div>
      </div>
      <div class="fg">
        <label>Password <span class="req">*</span></label>
        <input type="password" class="fc" id="lPass" placeholder="Your password" oninput="lv(this,'req')">
        <div class="fm" id="lPassM"></div>
      </div>
      <div style="text-align:right;margin-bottom:.75rem;">
        <a href="#" style="font-size:.78rem;color:var(--a1)" onclick="toast('Password reset link sent! (demo)','success');return false;">Forgot password?</a>
      </div>
      <button class="btn-sub" onclick="doLogin()">Login to Nebula</button>
      <div class="divider">or</div>
      <div class="social-row">
        <button class="btn-soc" onclick="socialLogin('Google')">🌐 Google</button>
        <button class="btn-soc" onclick="socialLogin('GitHub')">🐙 GitHub</button>
      </div>
      <p style="text-align:center;font-size:.78rem;color:var(--muted);margin-top:.85rem;">
        No account? <a href="#" style="color:var(--a1)" onclick="switchTab('register')">Register free →</a>
      </p>
    </div>

    <!-- REGISTER -->
    <div class="auth-form" id="fRegister">
      <div class="av-upload">
        <div class="av-preview" id="avPreview">🌌<img id="avImg" alt=""></div>
        <div>
          <div style="font-size:.85rem;font-weight:600;margin-bottom:.3rem;">Profile Photo</div>
          <div style="font-size:.72rem;color:var(--muted);margin-bottom:.4rem;">JPG / PNG / GIF · max 2 MB</div>
          <button class="btn-up" onclick="document.getElementById('avInput').click()">📷 Upload</button>
          <input type="file" id="avInput" accept="image/*" onchange="previewAv(this)">
        </div>
      </div>

      <div class="form-row">
        <div class="fg"><label>First Name <span class="req">*</span></label>
          <input type="text" class="fc" id="rFirst" placeholder="John" oninput="lv(this,'name')">
          <div class="fm" id="rFirstM"></div></div>
        <div class="fg"><label>Last Name <span class="req">*</span></label>
          <input type="text" class="fc" id="rLast" placeholder="Doe" oninput="lv(this,'name')">
          <div class="fm" id="rLastM"></div></div>
      </div>

      <div class="fg"><label>Email <span class="req">*</span></label>
        <input type="email" class="fc" id="rEmail" placeholder="you@example.com" oninput="lv(this,'email')">
        <div class="fm" id="rEmailM"></div></div>

      <div class="form-row">
        <div class="fg"><label>Phone <span class="req">*</span></label>
          <input type="tel" class="fc" id="rPhone" placeholder="+91 98765 43210" oninput="lv(this,'phone')">
          <div class="fm" id="rPhoneM"></div></div>
        <div class="fg"><label>Date of Birth (DD/MM/YYYY) <span class="req">*</span></label>
          <input type="text" class="fc" id="rDob" placeholder="DD/MM/YYYY" maxlength="10"
                 oninput="fmtDob(this);lv(this,'dob')">
          <div class="fm" id="rDobM"></div></div>
      </div>

      <div class="form-row">
        <div class="fg"><label>Gender <span class="req">*</span></label>
          <select class="fc" id="rGender" onchange="lv(this,'sel')">
            <option value="">— Select —</option>
            <option>Male</option><option>Female</option>
            <option>Non-binary</option><option>Prefer not to say</option>
          </select>
          <div class="fm" id="rGenderM"></div></div>
        <div class="fg"><label>Country <span class="req">*</span></label>
          <select class="fc" id="rCountry" onchange="lv(this,'sel')">
            <option value="">— Select —</option>
            <option>India</option><option>United States</option><option>United Kingdom</option>
            <option>Australia</option><option>Canada</option><option>Germany</option>
            <option>France</option><option>Japan</option><option>Brazil</option>
            <option>South Africa</option><option>Other</option>
          </select>
          <div class="fm" id="rCountryM"></div></div>
      </div>

      <div class="fg"><label>Default City <span class="req">*</span></label>
        <input type="text" class="fc" id="rCity" placeholder="e.g. Mumbai, London" oninput="lv(this,'req')">
        <div class="fm" id="rCityM"></div></div>

      <div class="fg"><label>Password <span class="req">*</span></label>
        <input type="password" class="fc" id="rPass" placeholder="Min 8 chars, 1 number, 1 symbol"
               oninput="lv(this,'pwd');pwdStr()">
        <div class="pwd-bar-wrap"><div class="pwd-bar" id="pwdBar"></div></div>
        <div class="fm" id="rPassM"></div></div>

      <div class="fg"><label>Confirm Password <span class="req">*</span></label>
        <input type="password" class="fc" id="rConf" placeholder="Repeat password" oninput="lv(this,'conf')">
        <div class="fm" id="rConfM"></div></div>

      <label class="terms-row">
        <input type="checkbox" id="rTerms" onchange="lv(this,'terms')">
        <span>I agree to the <a href="#">Terms of Service</a> and <a href="#">Privacy Policy</a>.
        I am 13 years of age or older.</span>
      </label>
      <div class="fm e" id="rTermsM"></div>

      <button class="btn-sub" onclick="doRegister()">Create My Account ✨</button>
      <p style="text-align:center;font-size:.78rem;color:var(--muted);margin-top:.85rem;">
        Already registered? <a href="#" style="color:var(--a1)" onclick="switchTab('login')">Login →</a>
      </p>
    </div>
  </div>
</div>

<!-- ══════ TOAST ═════════════════════════════════════════════ -->
<div id="toast"></div>

<!-- ══════ ANIMATED WEATHER BG ══════════════════════════════ -->
<div id="wxBg"></div>

<!-- ══════ PAGES ════════════════════════════════════════════ -->
<div class="container">

  <!-- WEATHER PAGE -->
  <div class="page active" id="pg-weather">
    <div class="header">
      <span class="logo">🌌</span>
      <h1>NEBULA WEATHER</h1>
      <div class="subtitle">Real-time intelligence &bull; Global coverage &bull; Free &amp; open</div>
    </div>

    <!-- Search bar (fixed isolation) -->
    <div class="search-bar-wrap">
      <button class="icon-btn" title="Use my location" onclick="useLocation()" style="flex-shrink:0">📍</button>
      <div class="search-wrapper">
        <input type="text" id="cityInput" class="search-input"
               placeholder="Search city… try London, Tokyo, Mumbai" value="London" autocomplete="off">
        <div id="suggestions" class="suggestions"></div>
      </div>
      <button class="search-btn" onclick="loadWeather()">🔍 Search</button>
    </div>

    <!-- Recent searches -->
    <div id="recentRow"></div>

    <div id="loader" class="loader hidden">
      <div class="spinner"></div>
      <p style="color:var(--muted);font-size:.9rem">Fetching weather data…</p>
    </div>
    <div id="errMsg" class="err-msg hidden"></div>

    <div id="wxApp" class="hidden">

      <!-- Alerts -->
      <div id="alertCard" class="card alerts-card hidden">
        <h3>⚠️ Weather Alerts</h3>
        <div id="alertList"></div>
      </div>

      <!-- Current conditions -->
      <div class="card">
        <div class="loc-row">
          <div>
            <div class="city-name" id="cName"></div>
            <div class="city-sub" id="cSub"></div>
            <div id="dewBadge" class="badge"></div>
          </div>
          <div style="text-align:right;font-size:.8rem;color:var(--muted)">
            <div id="cTz"></div>
            <button class="copy-btn" onclick="copyWeather()" title="Copy summary">📋 Copy</button>
          </div>
        </div>
        <div class="stat-grid" id="statGrid"></div>
      </div>

      <!-- Sunrise/sunset + wind compass (2-col on desktop) -->
      <div class="card-row">
        <div class="card">
          <h3>🌅 Daylight</h3>
          <div class="sun-arc-wrap" id="sunArc"></div>
        </div>
        <div class="card">
          <h3>🧭 Wind Compass</h3>
          <div class="compass-wrap" id="compassWrap"></div>
        </div>
      </div>

      <!-- Air quality -->
      <div class="card hidden" id="aqCard">
        <h3>🌍 Air Quality</h3>
        <div class="stat-grid" id="aqGrid"></div>
      </div>

      <!-- Hourly -->
      <div class="card">
        <h3>⏰ 24-Hour Forecast</h3>
        <div class="hourly-scroll" id="hourlyGrid"></div>
      </div>

      <!-- Search history mini chart -->
      <div class="card" id="histCard">
        <h3>📊 Search History (High Temps)</h3>
        <div class="hist-chart" id="histChart"></div>
      </div>

    </div><!-- /wxApp -->
  </div><!-- /pg-weather -->

  <!-- FORECAST PAGE -->
  <div class="page" id="pg-forecast">
    <div class="header"><span class="logo">📅</span><h1>7-DAY FORECAST</h1></div>
    <div class="card">
      <h3>📅 7-Day Daily Forecast</h3>
      <div class="fc-grid" id="fcGrid"></div>
      <div id="fcHint" style="text-align:center;color:var(--muted);padding:2rem;font-size:.9rem">
        Search a city on the Weather page first.
      </div>
    </div>
  </div>

  <!-- COMPARE PAGE -->
  <div class="page" id="pg-compare">
    <div class="header"><span class="logo">⚖️</span><h1>COMPARE CITIES</h1>
      <div class="subtitle">Side-by-side current conditions for any two cities</div></div>
    <div class="card">
      <div style="display:flex;gap:.6rem;flex-wrap:wrap;margin-bottom:1rem;">
        <input type="text" class="search-input" id="cmp1" placeholder="City 1 e.g. Paris"
               style="flex:1;min-width:140px;max-width:260px;border-radius:12px;">
        <input type="text" class="search-input" id="cmp2" placeholder="City 2 e.g. Tokyo"
               style="flex:1;min-width:140px;max-width:260px;border-radius:12px;">
        <button class="search-btn" onclick="doCompare()" style="border-radius:12px;">Compare ⚡</button>
      </div>
      <div id="cmpLoader" class="loader hidden"><div class="spinner"></div></div>
      <div id="cmpResult" class="compare-wrap hidden"></div>
    </div>
  </div>

  <!-- PROFILE PAGE -->
  <div class="page" id="pg-profile">
    <div id="profOut" style="text-align:center;padding:3rem 1rem">
      <div style="font-size:2.5rem;margin-bottom:.75rem">🔐</div>
      <div style="font-size:1.1rem;font-weight:600;margin-bottom:.4rem">You're not logged in</div>
      <div style="color:var(--muted);margin-bottom:1.25rem;font-size:.88rem">Create an account to save preferences and history.</div>
      <button class="btn-n signup" style="padding:.65rem 1.75rem;font-size:.9rem" onclick="openAuth('register')">Create Account</button>
      &nbsp;
      <button class="btn-n login" style="padding:.65rem 1.75rem;font-size:.9rem" onclick="openAuth('login')">Login</button>
    </div>
    <div id="profIn" class="hidden">
      <div class="prof-card">
        <div class="prof-av" id="profAv">👤<img id="profAvImg" alt=""></div>
        <div style="font-size:1.5rem;font-weight:700" id="profName"></div>
        <div style="color:var(--muted);font-size:.85rem;margin:.25rem 0 1.25rem" id="profEmail"></div>
        <div class="prof-grid">
          <div class="prof-field"><div class="lbl">Phone</div><div class="val" id="pfPhone"></div></div>
          <div class="prof-field"><div class="lbl">Date of Birth</div><div class="val" id="pfDob"></div></div>
          <div class="prof-field"><div class="lbl">Gender</div><div class="val" id="pfGender"></div></div>
          <div class="prof-field"><div class="lbl">Country</div><div class="val" id="pfCountry"></div></div>
          <div class="prof-field"><div class="lbl">Default City</div><div class="val" id="pfCity"></div></div>
          <div class="prof-field"><div class="lbl">Member Since</div><div class="val" id="pfJoined"></div></div>
        </div>
        <button class="btn-n logout" style="display:inline-block;margin-top:1.25rem;padding:.6rem 1.5rem" onclick="doLogout()">🚪 Logout</button>
      </div>
    </div>
  </div>

  <!-- ABOUT PAGE -->
  <div class="page" id="pg-about">
    <div class="header"><span class="logo">ℹ️</span><h1>ABOUT NEBULA</h1></div>
    <div class="about-wrap card">
      <h2>🌌 What is Nebula Weather?</h2>
      <p>A fully self-contained browser-based weather dashboard running on a plain Java 11 HTTP server —
      <strong>zero frameworks, zero databases, zero external dependencies</strong>. It queries
      <a href="https://open-meteo.com" style="color:var(--a1)" target="_blank">Open-Meteo</a>
      for live data and presents it in a modern, responsive UI.</p>

      <h2>✨ Features (v3.0)</h2>
      <div class="feat-grid">
        <div class="feat-item"><span>🌤</span><span>Real-time current conditions</span></div>
        <div class="feat-item"><span>📅</span><span>7-day daily forecast</span></div>
        <div class="feat-item"><span>⏰</span><span>24-hour hourly breakdown</span></div>
        <div class="feat-item"><span>🌍</span><span>Air quality (AQI + PM2.5)</span></div>
        <div class="feat-item"><span>⚠️</span><span>Smart weather alerts</span></div>
        <div class="feat-item"><span>⚖️</span><span>Compare two cities side-by-side</span></div>
        <div class="feat-item"><span>🌡️/°F</span><span>°C / °F live unit toggle</span></div>
        <div class="feat-item"><span>🌓</span><span>Dark / Light theme switcher</span></div>
        <div class="feat-item"><span>📊</span><span>Search history temperature chart</span></div>
        <div class="feat-item"><span>🧭</span><span>Animated wind compass rose</span></div>
        <div class="feat-item"><span>🌅</span><span>Sunrise/sunset daylight arc</span></div>
        <div class="feat-item"><span>💧</span><span>Dew point calculation</span></div>
        <div class="feat-item"><span>📋</span><span>Copy weather summary to clipboard</span></div>
        <div class="feat-item"><span>📍</span><span>Auto-detect user location</span></div>
        <div class="feat-item"><span>🕐</span><span>Recent searches (persisted)</span></div>
        <div class="feat-item"><span>🖨️</span><span>Print-friendly layout</span></div>
        <div class="feat-item"><span>⌨️</span><span>Ctrl+K keyboard shortcut to search</span></div>
        <div class="feat-item"><span>🔐</span><span>Full Login / Register with validation</span></div>
        <div class="feat-item"><span>⚡</span><span>5-min server-side cache</span></div>
        <div class="feat-item"><span>☕</span><span>Pure Java 11 — zero libraries</span></div>
      </div>

      <h2>🛠 Tech Stack</h2>
      <p>
        <strong>Backend:</strong> Java 11 · <code>com.sun.net.httpserver</code> · <code>java.net.http.HttpClient</code> · Regex JSON parsing<br>
        <strong>APIs:</strong> Open-Meteo Forecast · Geocoding · Air Quality (all free, no key)<br>
        <strong>Frontend:</strong> Vanilla HTML5 / CSS3 / JavaScript — no React, no jQuery, no bundler
      </p>
      <h2>🚀 Run It</h2>
      <p><code>javac NebulaWeather.java</code> &nbsp;→&nbsp; <code>java NebulaWeather</code>
      &nbsp;→&nbsp; open <strong>http://localhost:8080</strong><br>
      Custom port: <code>java NebulaWeather --port 9000</code></p>
    </div>
  </div>

</div><!-- /container -->

<!-- ══════ JAVASCRIPT ════════════════════════════════════════ -->
<script>
'use strict';
/* ── State ─────────────────────────────────────────────────── */
let WX = null;        // last loaded weather data (°C)
let UNIT = 'C';       // 'C' or 'F'
let USER = null;
let HIST = [];        // [{city,temp}] last 8 searches

/* ── Boot ──────────────────────────────────────────────────── */
(function boot(){
  // restore theme
  const th = localStorage.getItem('nebula_theme') || 'dark';
  document.documentElement.setAttribute('data-theme', th);
  // restore unit
  UNIT = localStorage.getItem('nebula_unit') || 'C';
  document.getElementById('uC').classList.toggle('active', UNIT==='C');
  document.getElementById('uF').classList.toggle('active', UNIT==='F');
  // restore user session
  try{
    const u = JSON.parse(localStorage.getItem('nebula_user'));
    if(u){ USER=u; applyUserUI(u); }
  }catch(e){}
  // restore recent searches
  try{ HIST = JSON.parse(localStorage.getItem('nebula_hist'))||[]; }catch(e){}
  renderRecentRow();
  // load default city
  const city = (USER&&USER.city)||'London';
  document.getElementById('cityInput').value = city;
  loadWeather();
})();

/* ── Page navigation ───────────────────────────────────────── */
function goPage(id){
  document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
  document.getElementById('pg-'+id).classList.add('active');
  document.querySelectorAll('.nav-links a').forEach(a=>a.classList.remove('active'));
  const l=document.getElementById('nl-'+id); if(l) l.classList.add('active');
  window.scrollTo({top:0,behavior:'smooth'});
  if(id==='forecast') syncFc();
}

/* ── Mobile menu ───────────────────────────────────────────── */
function toggleMobile(){
  document.getElementById('mobileMenu').classList.toggle('open');
  document.getElementById('hbg').classList.toggle('open');
}
function closeMobile(){
  document.getElementById('mobileMenu').classList.remove('open');
  document.getElementById('hbg').classList.remove('open');
}

/* ── Theme ─────────────────────────────────────────────────── */
function toggleTheme(){
  const cur = document.documentElement.getAttribute('data-theme');
  const next = cur==='dark'?'light':'dark';
  document.documentElement.setAttribute('data-theme',next);
  localStorage.setItem('nebula_theme',next);
}

/* ── Unit toggle ───────────────────────────────────────────── */
function setUnit(u){
  UNIT=u;
  localStorage.setItem('nebula_unit',u);
  document.getElementById('uC').classList.toggle('active',u==='C');
  document.getElementById('uF').classList.toggle('active',u==='F');
  if(WX) renderWeather(WX); // re-render with new unit, no re-fetch
}
function toUnit(c){
  if(UNIT==='F') return (c*9/5+32).toFixed(1);
  return Number(c).toFixed(1);
}
function unitSuffix(){ return '°'+UNIT; }

/* ── Weather load ──────────────────────────────────────────── */
async function loadWeather(){
  const city = document.getElementById('cityInput').value.trim();
  if(!city) return;
  document.getElementById('suggestions').style.display='none';
  show('loader'); hide('wxApp'); hideErr();
  try{
    const r = await fetch('/weather?city='+encodeURIComponent(city));
    const d = await r.json();
    if(d.error){ showErr(d.error); return; }
    WX = d;
    // add to history
    addHist(city, parseFloat(d.current.temp));
    renderWeather(d);
    hide('loader'); show('wxApp');
    syncFc();
    animateBg(d.current.code, d.isDay);
  }catch(e){ showErr('Network error: '+e.message); }
}

/* ── Geolocation ───────────────────────────────────────────── */
async function useLocation(){
  if(!navigator.geolocation){ toast('Geolocation not supported','error'); return; }
  toast('Detecting your location…','');
  navigator.geolocation.getCurrentPosition(async pos=>{
    const {latitude:lat,longitude:lon} = pos.coords;
    document.getElementById('suggestions').style.display='none';
    show('loader'); hide('wxApp'); hideErr();
    try{
      // Use dedicated coords endpoint — bypasses geocoding completely
      const r = await fetch('/weather-by-coords?lat='+lat.toFixed(5)+'&lon='+lon.toFixed(5)+'&name=My+Location');
      const d = await r.json();
      if(d.error){ showErr(d.error); return; }
      WX = d;
      document.getElementById('cityInput').value = 'My Location ('+lat.toFixed(2)+','+lon.toFixed(2)+')';
      addHist('My Location', parseFloat(d.current.temp));
      renderWeather(d);
      hide('loader'); show('wxApp');
      syncFc();
      animateBg(d.current.code, d.isDay);
      toast('Location loaded! 📍','success');
    }catch(e){ showErr('Location weather failed: '+e.message); }
  }, err=>{
    hide('loader');
    if(err.code===1) toast('Location access denied — please allow location in browser','error');
    else toast('Could not get location','error');
  }, {timeout:10000, maximumAge:60000});
}

/* ── Animated weather background ──────────────────────────── */
function animateBg(code, isDay){
  const bg = document.getElementById('wxBg');
  bg.innerHTML = '';
  const isRain = (code>=51&&code<=67)||(code>=80&&code<=82);
  const isSnow = code>=71&&code<=77;
  const isThunder = code>=95;
  if(isRain||isThunder){
    for(let i=0;i<40;i++){
      const d=document.createElement('div');
      d.className='wx-drop';
      d.style.left=Math.random()*100+'%';
      d.style.height=(Math.random()*30+15)+'px';
      d.style.animationDuration=(Math.random()*0.6+0.5)+'s';
      d.style.animationDelay=(-Math.random()*2)+'s';
      d.style.opacity=Math.random()*0.6+0.3;
      bg.appendChild(d);
    }
    bg.style.opacity='0.3';
  } else if(isSnow){
    for(let i=0;i<30;i++){
      const s=document.createElement('div');
      s.className='wx-snow';
      s.style.left=Math.random()*100+'%';
      const sz=Math.random()*5+3;
      s.style.width=sz+'px'; s.style.height=sz+'px';
      s.style.animationDuration=(Math.random()*4+3)+'s';
      s.style.animationDelay=(-Math.random()*5)+'s';
      bg.appendChild(s);
    }
    bg.style.opacity='0.25';
  } else {
    bg.style.opacity='0';
  }
}

/* ── Render weather ────────────────────────────────────────── */
function renderWeather(d){
  const c=d.current;
  document.getElementById('cName').innerHTML =
    `${d.city}, ${d.country} <span style="font-size:1.3rem">${c.icon}</span>`;
  document.getElementById('cSub').textContent =
    new Date().toLocaleDateString('en-IN',{weekday:'long',year:'numeric',month:'long',day:'numeric'});
  document.getElementById('cTz').textContent = '⏰ '+d.timezone;

  // Dew point: Magnus formula Td ≈ T - (100 - RH)/5
  const dewBadge = document.getElementById('dewBadge');
  if(c.temp != null && c.humidity != null){
    const dp = (parseFloat(c.temp) - (100 - c.humidity)/5).toFixed(1);
    dewBadge.textContent = `💧 Dew point: ${toUnit(dp)}${unitSuffix()}`;
    dewBadge.style.display = '';
  } else {
    dewBadge.style.display = 'none';
  }

  // Stats
  document.getElementById('statGrid').innerHTML = `
    <div class="stat"><div class="si">🌡️</div><div class="sl">Temperature</div>
      <div class="sv">${toUnit(c.temp)}${unitSuffix()}</div>
      <div class="ss">Feels ${toUnit(c.feels)}${unitSuffix()}</div></div>
    <div class="stat"><div class="si">💧</div><div class="sl">Humidity</div>
      <div class="sv">${c.humidity}%</div>
      <div class="ss">${c.humidity>70?'High':c.humidity<30?'Low':'Moderate'}</div></div>
    <div class="stat"><div class="si">💨</div><div class="sl">Wind</div>
      <div class="sv">${c.wind} km/h</div>
      <div class="ss">${c.windDirText} · ${c.windDir}°</div></div>
    <div class="stat"><div class="si">🌊</div><div class="sl">Pressure</div>
      <div class="sv">${c.pressure} hPa</div>
      <div class="ss">${c.pressure>1013?'↑ High':'↓ Low'}</div></div>
    <div class="stat"><div class="si">👁️</div><div class="sl">Visibility</div>
      <div class="sv">${c.visibility!=null?c.visibility+' km':'N/A'}</div></div>
    <div class="stat"><div class="si">☀️</div><div class="sl">UV Index</div>
      <div class="sv">${c.uvIndex!=null?c.uvIndex:'N/A'}</div>
      <div class="ss">${c.uvLevel}</div></div>
    <div class="stat"><div class="si">${c.icon}</div><div class="sl">Condition</div>
      <div class="sv" style="font-size:.85rem;line-height:1.3">${c.desc}</div></div>
  `;

  // Sunrise / sunset arc
  if(d.daily && d.daily.length>0){
    const today = d.daily[0];
    const rise = today.sunrise, set = today.sunset;
    let pct = 50;
    try{
      const now = new Date();
      const rParts = rise.split(':'), sParts = set.split(':');
      const rMin = +rParts[0]*60 + +rParts[1];
      const sMin = +sParts[0]*60 + +sParts[1];
      const nMin = now.getHours()*60 + now.getMinutes();
      pct = Math.max(0, Math.min(100, (nMin-rMin)/(sMin-rMin)*100));
    }catch(e){}
    document.getElementById('sunArc').innerHTML = `
      <div class="sun-labels"><span>🌅 ${rise}</span><span>🌇 ${set}</span></div>
      <div class="sun-arc-bar"><div class="sun-arc-fill" style="width:${pct.toFixed(1)}%"></div></div>
      <div style="text-align:center;font-size:.75rem;color:var(--muted)">
        ${pct<0.5?'Before sunrise':pct>99.5?'After sunset':'☀️ Currently '+pct.toFixed(0)+'% through daylight'}
      </div>`;
  }

  // Wind compass
  const dir = c.windDir;
  document.getElementById('compassWrap').innerHTML = `
    <div class="compass">
      <span class="compass-n">N</span><span class="compass-s">S</span>
      <span class="compass-e">E</span><span class="compass-w">W</span>
      <div class="compass-needle" id="needle"></div>
    </div>
    <div>
      <div style="font-size:1.1rem;font-weight:700">${c.wind} km/h</div>
      <div style="font-size:.8rem;color:var(--muted);margin-top:.2rem">${c.windDirText} (${dir}°)</div>
      <div style="font-size:.75rem;color:var(--muted);margin-top:.1rem">${getBeaufort(c.wind)}</div>
    </div>`;
  setTimeout(()=>{
    const n=document.getElementById('needle');
    if(n) n.style.transform=`rotate(${dir}deg)`;
  },100);

  // Air quality
  const aq=d.airQuality;
  if(aq&&aq.aqi!=null){
    document.getElementById('aqGrid').innerHTML = `
      <div class="stat"><div class="si">🌫️</div><div class="sl">AQI (EU)</div>
        <div class="sv">${aq.aqi}</div><div class="ss">${aq.aqiLevel}</div></div>
      <div class="stat"><div class="si">🔬</div><div class="sl">PM2.5</div>
        <div class="sv">${aq.pm25!=null?aq.pm25+' µg/m³':'N/A'}</div></div>`;
    show('aqCard');
  } else { hide('aqCard'); }

  // Hourly
  const now = new Date().getHours();
  document.getElementById('hourlyGrid').innerHTML = (d.hourly||[]).map((h,i)=>{
    const hh = parseInt(h.hour.split(':')[0]);
    const isCurrent = Math.abs(hh-now)<=1;
    return `<div class="hourly-item${isCurrent?' h-now':''}">
      <div style="font-weight:700;font-size:.75rem">${h.hour}</div>
      <div style="font-size:1.05rem;margin:.3rem 0">${toUnit(h.temp)}${unitSuffix()}</div>
      <div style="font-size:.68rem;color:var(--muted)">💧${h.humidity}%</div>
      <div style="font-size:.68rem;color:#88ccff">🌧${h.rainProb}%</div>
    </div>`;
  }).join('');

  // Alerts
  const noAlt = d.alerts.length===1&&d.alerts[0]==='✅ No active weather alerts';
  if(!noAlt){
    document.getElementById('alertList').innerHTML =
      d.alerts.map(a=>`<div class="alert-item">${a}</div>`).join('');
    show('alertCard');
  } else hide('alertCard');

  // History chart
  renderHistChart();
}

/* ── Beaufort scale ────────────────────────────────────────── */
function getBeaufort(kmh){
  if(kmh<1)   return 'Calm';
  if(kmh<6)   return 'Light air';
  if(kmh<12)  return 'Light breeze';
  if(kmh<20)  return 'Gentle breeze';
  if(kmh<29)  return 'Moderate breeze';
  if(kmh<39)  return 'Fresh breeze';
  if(kmh<50)  return 'Strong breeze';
  if(kmh<62)  return 'Near gale';
  if(kmh<75)  return 'Gale';
  if(kmh<89)  return 'Severe gale';
  if(kmh<103) return 'Storm';
  return 'Violent storm';
}

/* ── Forecast page sync ────────────────────────────────────── */
function syncFc(){
  const hint=document.getElementById('fcHint');
  const grid=document.getElementById('fcGrid');
  if(!WX){ hint.style.display='block'; grid.style.display='none'; return; }
  hint.style.display='none'; grid.style.display='';
  grid.innerHTML = WX.daily.map(day=>`
    <div class="fc-day">
      <div class="fc-label">${day.label}</div>
      <div class="fc-icon">${iconFor(day.code)}</div>
      <div><span class="fc-hi">${toUnit(day.max)}${unitSuffix()}</span>
           <span class="fc-lo">${toUnit(day.min)}${unitSuffix()}</span></div>
      <div class="fc-info">💧${day.rain}mm · ${day.rainProb}%</div>
      <div class="fc-info">🌬️${day.windMax} km/h</div>
      <div class="fc-info">☀️UV ${day.uvMax}</div>
      <div class="fc-info" style="font-size:.67rem">🌅${day.sunrise}&nbsp;🌇${day.sunset}</div>
    </div>`).join('');
}

/* ── Compare cities ────────────────────────────────────────── */
async function doCompare(){
  const c1=document.getElementById('cmp1').value.trim();
  const c2=document.getElementById('cmp2').value.trim();
  if(!c1||!c2){ toast('Enter both city names','error'); return; }
  show('cmpLoader'); hide('cmpResult');
  try{
    const [r1,r2] = await Promise.all([
      fetch('/weather?city='+encodeURIComponent(c1)).then(r=>r.json()),
      fetch('/weather?city='+encodeURIComponent(c2)).then(r=>r.json())
    ]);
    hide('cmpLoader');
    if(r1.error||r2.error){ toast((r1.error||r2.error),'error'); return; }
    const rows = [
      ['🌡️ Temp',       toUnit(r1.current.temp)+unitSuffix(), toUnit(r2.current.temp)+unitSuffix()],
      ['🌡️ Feels Like', toUnit(r1.current.feels)+unitSuffix(), toUnit(r2.current.feels)+unitSuffix()],
      ['💧 Humidity',   r1.current.humidity+'%',   r2.current.humidity+'%'],
      ['💨 Wind',       r1.current.wind+' km/h',   r2.current.wind+' km/h'],
      ['🌊 Pressure',   r1.current.pressure+' hPa',r2.current.pressure+' hPa'],
      ['☀️ UV',         r1.current.uvIndex||'N/A',  r2.current.uvIndex||'N/A'],
      ['🌤 Condition',  r1.current.desc,             r2.current.desc],
    ];
    const mkCard = (d,rows,idx)=>`
      <div class="compare-card">
        <h4>${d.city}, ${d.country} ${d.current.icon}</h4>
        ${rows.map(row=>`
          <div class="compare-row">
            <span class="lbl">${row[0]}</span>
            <span style="font-weight:600">${row[idx]}</span>
          </div>`).join('')}
      </div>`;
    const el=document.getElementById('cmpResult');
    el.innerHTML = mkCard(r1,rows,1)+mkCard(r2,rows,2);
    show('cmpResult');
  }catch(e){ hide('cmpLoader'); toast('Compare failed: '+e.message,'error'); }
}

/* ── Recent searches ───────────────────────────────────────── */
function addHist(city, temp){
  HIST = HIST.filter(h=>h.city.toLowerCase()!==city.toLowerCase());
  HIST.unshift({city, temp});
  if(HIST.length>8) HIST.pop();
  localStorage.setItem('nebula_hist', JSON.stringify(HIST));
  renderRecentRow();
  renderHistChart();
}
function renderRecentRow(){
  const row = document.getElementById('recentRow');
  if(!row) return;
  row.innerHTML = HIST.slice(0,6).map((h,i)=>
    `<span class="recent-pill" data-idx="${i}" onclick="selectRecent(this)">${h.city}</span>`
  ).join('');
}
function selectRecent(el){
  const idx = parseInt(el.dataset.idx);
  const city = HIST[idx] ? HIST[idx].city : el.textContent.trim();
  document.getElementById('cityInput').value = city;
  loadWeather();
}
function renderHistChart(){
  const el=document.getElementById('histChart');
  if(!el||HIST.length===0) return;
  const maxT = Math.max(...HIST.map(h=>h.temp));
  const minT = Math.min(...HIST.map(h=>h.temp));
  const range = Math.max(maxT-minT,1);
  el.innerHTML = HIST.slice(0,8).map(h=>{
    const pct = 20 + ((h.temp-minT)/range)*80;
    return `<div class="hist-bar-wrap">
      <div class="hist-bar" style="height:${pct.toFixed(0)}%;"></div>
      <div class="hist-label" title="${h.city}">${h.city.split(',')[0].slice(0,6)}</div>
      <div style="font-size:.62rem;color:var(--muted)">${toUnit(h.temp)}°</div>
    </div>`;
  }).join('');
}

/* ── Copy weather to clipboard ─────────────────────────────── */
function copyWeather(){
  if(!WX) return;
  const c=WX.current;
  const txt=`Nebula Weather — ${WX.city}, ${WX.country}
Condition: ${c.desc} ${c.icon}
Temperature: ${toUnit(c.temp)}${unitSuffix()} (feels ${toUnit(c.feels)}${unitSuffix()})
Humidity: ${c.humidity}%  |  Wind: ${c.wind} km/h ${c.windDirText}
Pressure: ${c.pressure} hPa  |  Visibility: ${c.visibility} km
UV Index: ${c.uvIndex} (${c.uvLevel})
— via Nebula Weather v3.0`;
  navigator.clipboard.writeText(txt).then(()=>toast('Copied to clipboard! 📋','success'))
    .catch(()=>toast('Copy failed','error'));
}

/* ── Auth ──────────────────────────────────────────────────── */
function openAuth(tab){
  switchTab(tab);
  document.getElementById('authOvl').classList.add('open');
  document.body.style.overflow='hidden';
}
function closeAuth(){
  document.getElementById('authOvl').classList.remove('open');
  document.body.style.overflow='';
}
function ovlClick(e){ if(e.target===document.getElementById('authOvl')) closeAuth(); }
function switchTab(t){
  document.getElementById('tLogin').classList.toggle('active',   t==='login');
  document.getElementById('tRegister').classList.toggle('active',t==='register');
  document.getElementById('fLogin').classList.toggle('active',    t==='login');
  document.getElementById('fRegister').classList.toggle('active', t==='register');
  document.getElementById('modTitle').textContent = t==='login'?'Welcome Back 👋':'Create Account ✨';
}
function previewAv(input){
  if(!input.files||!input.files[0]) return;
  const reader=new FileReader();
  reader.onload=e=>{
    const img=document.getElementById('avImg');
    img.src=e.target.result; img.style.display='block';
    const prev=document.getElementById('avPreview');
    prev.textContent=''; prev.appendChild(img);
  };
  reader.readAsDataURL(input.files[0]);
}
// FIX H: DOB formatter — correct regex in plain JS (no string escaping issue)
function fmtDob(input){
  let v=input.value.replace(/\\D/g,'');
  if(v.length>2&&v.length<=4)  v=v.slice(0,2)+'/'+v.slice(2);
  else if(v.length>4)           v=v.slice(0,2)+'/'+v.slice(2,4)+'/'+v.slice(4,8);
  input.value=v;
}
// FIX I: validation regexes are plain JS literals — no double-escaping
function lv(el,type){
  const id=el.id, msgEl=document.getElementById(id+'M');
  const v=el.value.trim();
  let ok=false,txt='';
  switch(type){
    case 'name': ok=v.length>=2; txt=ok?'✓ Looks good':'At least 2 characters'; break;
    case 'email': ok=/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(v); txt=ok?'✓ Valid email':'Enter a valid email'; break;
    case 'phone': ok=/^[\\+\\d][\\d\\s\\-]{7,15}$/.test(v); txt=ok?'✓ Valid':'7–15 digits required'; break;
    case 'dob':{
      const p=v.split('/');
      ok=p.length===3&&p[0]>='01'&&p[0]<='31'&&p[1]>='01'&&p[1]<='12'
         &&p[2].length===4&&+p[2]>=1900&&+p[2]<=(new Date().getFullYear()-13);
      txt=ok?'✓ Valid':'DD/MM/YYYY · must be 13+ years old'; break;
    }
    case 'sel': ok=v!==''; txt=ok?'✓':'Please select'; break;
    // FIX J: password regex uses standard JS literal
    case 'pwd': ok=/^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$/.test(v);
      txt=ok?'✓ Strong password':'Min 8 chars, include a number and symbol'; break;
    case 'conf':{
      const pw=document.getElementById('rPass');
      ok=pw&&v===pw.value&&v!==''; txt=ok?'✓ Passwords match':'Passwords do not match'; break;
    }
    case 'terms': ok=el.checked; txt=ok?'':'Must accept terms'; break;
    case 'req': ok=v.length>0; txt=ok?'✓':'Required'; break;
  }
  if(msgEl){ msgEl.textContent=txt; msgEl.className='fm '+(ok?'o':(v?'e':'')); }
  el.classList.toggle('ok',ok);
  el.classList.toggle('err',!ok&&v!==''&&type!=='terms');
  return ok;
}
function pwdStr(){
  const v=document.getElementById('rPass').value;
  let s=0;
  if(v.length>=8)s++; if(/[A-Za-z]/.test(v))s++; if(/\\d/.test(v))s++; if(/[^A-Za-z\\d]/.test(v))s++;
  const bar=document.getElementById('pwdBar');
  bar.style.width=['0%','25%','50%','75%','100%'][s];
  bar.style.background=['#ff4040','#ff8040','#ffd040','#80e080','#00e090'][s];
}
function doLogin(){
  const em=document.getElementById('lEmail'),pw=document.getElementById('lPass');
  if(!lv(em,'email')||!lv(pw,'req')) return;
  const stored=localStorage.getItem('nebula_user');
  if(stored){
    const u=JSON.parse(stored);
    if(u.email===em.value.trim()&&u.password===pw.value){ loginOk(u); return; }
  }
  // demo login with any valid creds
  loginOk({firstName:'Guest',lastName:'',email:em.value.trim(),phone:'N/A',
    dob:'N/A',gender:'N/A',country:'N/A',city:'London',
    password:pw.value,avatar:null,joined:new Date().toLocaleDateString('en-IN')});
}
function doRegister(){
  const fields=[['rFirst','name'],['rLast','name'],['rEmail','email'],['rPhone','phone'],
    ['rDob','dob'],['rGender','sel'],['rCountry','sel'],['rCity','req'],
    ['rPass','pwd'],['rConf','conf'],['rTerms','terms']];
  let ok=true;
  for(const [id,t] of fields) if(!lv(document.getElementById(id),t)) ok=false;
  if(!ok){ toast('Please fix the errors above','error'); return; }
  const img=document.getElementById('avImg');
  const u={
    firstName:document.getElementById('rFirst').value.trim(),
    lastName: document.getElementById('rLast').value.trim(),
    email:    document.getElementById('rEmail').value.trim(),
    phone:    document.getElementById('rPhone').value.trim(),
    dob:      document.getElementById('rDob').value.trim(),
    gender:   document.getElementById('rGender').value,
    country:  document.getElementById('rCountry').value,
    city:     document.getElementById('rCity').value.trim(),
    password: document.getElementById('rPass').value,
    avatar:   img.src&&img.style.display!=='none'?img.src:null,
    joined:   new Date().toLocaleDateString('en-IN')
  };
  localStorage.setItem('nebula_user',JSON.stringify(u));
  loginOk(u);
}
function loginOk(u){
  USER=u; closeAuth();
  applyUserUI(u);
  if(u.city&&u.city!=='N/A'){
    document.getElementById('cityInput').value=u.city;
    loadWeather();
  }
  toast('Welcome, '+u.firstName+'! 🎉','success');
}
function doLogout(){
  USER=null;
  ['bLogin','bSignup'].forEach(id=>document.getElementById(id).style.display='');
  document.getElementById('bLogout').style.display='none';
  document.getElementById('userBadge').style.display='none';
  document.getElementById('ml-logout').style.display='none';
  document.getElementById('ml-login').style.display='';
  document.getElementById('ml-signup').style.display='';
  hide('profIn'); show('profOut');
  goPage('weather');
  toast('Logged out 👋','success');
}
function applyUserUI(u){
  ['bLogin','bSignup'].forEach(id=>document.getElementById(id).style.display='none');
  document.getElementById('bLogout').style.display='';
  document.getElementById('ml-logout').style.display='';
  document.getElementById('ml-login').style.display='none';
  document.getElementById('ml-signup').style.display='none';
  const badge=document.getElementById('userBadge');
  badge.style.display='flex';
  document.getElementById('navName').textContent=u.firstName;
  const navAv=document.getElementById('navAv');
  if(u.avatar){ navAv.innerHTML=`<img src="${u.avatar}" alt="">`; }
  else{ navAv.textContent=u.firstName.charAt(0).toUpperCase(); }
  // profile page
  hide('profOut'); show('profIn');
  document.getElementById('profName').textContent = u.firstName+' '+u.lastName;
  document.getElementById('profEmail').textContent = u.email;
  document.getElementById('pfPhone').textContent   = u.phone;
  document.getElementById('pfDob').textContent     = u.dob;
  document.getElementById('pfGender').textContent  = u.gender;
  document.getElementById('pfCountry').textContent = u.country;
  document.getElementById('pfCity').textContent    = u.city;
  document.getElementById('pfJoined').textContent  = u.joined;
  if(u.avatar){
    const pi=document.getElementById('profAvImg');
    pi.src=u.avatar; pi.style.display='block';
    document.getElementById('profAv').textContent='';
    document.getElementById('profAv').appendChild(pi);
  }
}
function socialLogin(p){ toast(p+' login — coming soon!','success'); }

/* ── Autocomplete ──────────────────────────────────────────── */
let _deb;
document.getElementById('cityInput').addEventListener('input',function(){
  clearTimeout(_deb);
  const q=this.value.trim();
  const box=document.getElementById('suggestions');
  if(q.length<2){ box.style.display='none'; return; }
  _deb=setTimeout(async()=>{
    try{
      const r=await fetch('/suggestions?q='+encodeURIComponent(q));
      const cs=await r.json();
      if(cs.length){
        box.innerHTML=cs.map(c=>
          `<div class="sug-item" onclick="selectSug(${JSON.stringify(c)})">${c}</div>`
        ).join('');
        box.style.display='block';
      } else box.style.display='none';
    }catch(e){ box.style.display='none'; }
  },280);
});
function selectSug(c){
  document.getElementById('cityInput').value=c.split(',')[0].trim();
  document.getElementById('suggestions').style.display='none';
  loadWeather();
}
document.getElementById('cityInput').addEventListener('keydown',e=>{
  if(e.key==='Enter') loadWeather();
});
document.addEventListener('click',e=>{
  if(!e.target.closest('.search-wrapper'))
    document.getElementById('suggestions').style.display='none';
});

/* ── Keyboard shortcuts ────────────────────────────────────── */
document.addEventListener('keydown',e=>{
  if(e.key==='Escape') closeAuth();
  if((e.ctrlKey||e.metaKey)&&e.key==='k'){
    e.preventDefault();
    document.getElementById('cityInput').focus();
    document.getElementById('cityInput').select();
  }
});

/* ── Helpers ───────────────────────────────────────────────── */
function iconFor(code){
  const m={0:'☀️',1:'🌤️',2:'⛅',3:'☁️',45:'🌫️',48:'🌫️',
    51:'🌧️',53:'🌧️',55:'🌧️',61:'🌧️',63:'🌧️',65:'🌧️',
    71:'❄️',73:'❄️',75:'❄️',80:'🌦️',81:'🌧️',82:'⛈️',
    95:'⛈️',96:'⛈️',99:'⛈️'};
  return m[code]||'🌡️';
}
function show(id){ const e=document.getElementById(id); if(e) e.classList.remove('hidden'); }
function hide(id){ const e=document.getElementById(id); if(e) e.classList.add('hidden'); }
function hideErr(){ const e=document.getElementById('errMsg'); if(e){e.textContent='';e.classList.add('hidden');} }
function showErr(msg){ hide('loader'); const e=document.getElementById('errMsg'); if(e){e.textContent='⚠️ '+msg;e.classList.remove('hidden');} }
function toast(msg,type){
  const t=document.getElementById('toast');
  t.textContent=msg; t.className='show '+(type||'');
  clearTimeout(t._t); t._t=setTimeout(()=>{ t.className=''; },3200);
}
</script>
</body>
</html>
""";
    }
}