import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Scanner;

/**
 * WeatherApp — Java console app using Open-Meteo (free, no API key required)
 * Requires Java 11+  (uses java.net.http.HttpClient)
 *
 * Compile:  javac WeatherApp.java
 * Run:      java WeatherApp
 */
public class WeatherApp {

    static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
    static final String WX_URL  = "https://api.open-meteo.com/v1/forecast";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    // ── WMO weather-code descriptions ────────────────────────────────────────
    static String wmoDescription(int code) {
        return switch (code) {
            case 0  -> "Clear sky";
            case 1  -> "Mainly clear";
            case 2  -> "Partly cloudy";
            case 3  -> "Overcast";
            case 45 -> "Fog";
            case 48 -> "Icy fog";
            case 51 -> "Light drizzle";
            case 53 -> "Moderate drizzle";
            case 55 -> "Dense drizzle";
            case 61 -> "Slight rain";
            case 63 -> "Moderate rain";
            case 65 -> "Heavy rain";
            case 71 -> "Slight snow";
            case 73 -> "Moderate snow";
            case 75 -> "Heavy snow";
            case 80 -> "Slight showers";
            case 81 -> "Moderate showers";
            case 82 -> "Violent showers";
            case 95 -> "Thunderstorm";
            case 96 -> "Thunderstorm + hail";
            case 99 -> "Thunderstorm + heavy hail";
            default -> "Unknown (" + code + ")";
        };
    }

    // ── Minimal JSON helpers (no external library needed) ────────────────────

    static double jsonDouble(String json, String key) {
        String token = "\"" + key + "\":";
        int idx = json.indexOf(token);
        if (idx < 0) return Double.NaN;
        int start = idx + token.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        return Double.parseDouble(json.substring(start, end));
    }

    static int jsonInt(String json, String key) {
        return (int) Math.round(jsonDouble(json, key));
    }

    static String jsonString(String json, String key) {
        String token = "\"" + key + "\":\"";
        int idx = json.indexOf(token);
        if (idx < 0) return "";
        int start = idx + token.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    static double[] jsonDoubleArray(String json, String key) {
        String token = "\"" + key + "\":[";
        int idx = json.indexOf(token);
        if (idx < 0) return new double[0];
        int start = idx + token.length();
        int end = json.indexOf(']', start);
        String[] parts = json.substring(start, end).split(",");
        double[] vals = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            vals[i] = p.equals("null") ? 0.0 : Double.parseDouble(p);
        }
        return vals;
    }

    static int[] jsonIntArray(String json, String key) {
        double[] d = jsonDoubleArray(json, key);
        int[] r = new int[d.length];
        for (int i = 0; i < d.length; i++) r[i] = (int) Math.round(d[i]);
        return r;
    }

    static String[] jsonStringArray(String json, String key) {
        String token = "\"" + key + "\":[";
        int idx = json.indexOf(token);
        if (idx < 0) return new String[0];
        int start = idx + token.length();
        int end = json.indexOf(']', start);
        String[] parts = json.substring(start, end).split(",");
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++)
            result[i] = parts[i].trim().replace("\"", "");
        return result;
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    static double[] geocode(String city) throws Exception {
        String url = GEO_URL + "?name=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                + "&count=1&language=en&format=json";
        String body = get(url);

        if (!body.contains("\"results\"") || body.contains("\"results\":[]"))
            return null;

        int resStart = body.indexOf("\"results\":[{") + "\"results\":[".length();
        String result = body.substring(resStart);

        double lat     = jsonDouble(result, "latitude");
        double lon     = jsonDouble(result, "longitude");
        String name    = jsonString(result, "name");
        String country = jsonString(result, "country");
        String tz      = jsonString(result, "timezone");

        System.out.println("\n📍 Location : " + name + ", " + country);
        System.out.println("   Timezone  : " + tz);
        System.out.printf ("   Coords    : %.4f°N, %.4f°E%n", lat, lon);

        return new double[]{ lat, lon };
    }

    // ── Weather fetch & display ───────────────────────────────────────────────

    static void fetchAndDisplay(double lat, double lon) throws Exception {
        String url = WX_URL
                + "?latitude="  + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                +   "wind_speed_10m,weather_code,surface_pressure,visibility"
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code,"
                +   "precipitation_sum,wind_speed_10m_max"
                + "&timezone=auto"
                + "&forecast_days=7";

        String body = get(url);

        // ── Current conditions ────────────────────────────────────────────────
        int    wxCode   = jsonInt(body, "weather_code");
        double temp     = jsonDouble(body, "temperature_2m");
        double feels    = jsonDouble(body, "apparent_temperature");
        int    humidity = jsonInt(body, "relative_humidity_2m");
        double wind     = jsonDouble(body, "wind_speed_10m");
        double pressure = jsonDouble(body, "surface_pressure");
        double vis      = jsonDouble(body, "visibility");

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────┐");
        System.out.println("│           CURRENT CONDITIONS                │");
        System.out.println("├─────────────────────────────────────────────┤");
        System.out.printf ("│  Weather   : %-32s│%n", wmoDescription(wxCode));
        System.out.printf ("│  Temp      : %.1f°C  (feels like %.1f°C)%n", temp, feels);
        System.out.printf ("│  Humidity  : %d%%%n", humidity);
        System.out.printf ("│  Wind      : %.1f km/h%n", wind);
        System.out.printf ("│  Pressure  : %.1f hPa%n", pressure);
        if (!Double.isNaN(vis))
            System.out.printf("│  Visibility: %s%n",
                    vis >= 1000 ? String.format("%.1f km", vis / 1000) : String.format("%.0f m", vis));
        System.out.println("└─────────────────────────────────────────────┘");

        // ── 7-day forecast ────────────────────────────────────────────────────
        String[] dates   = jsonStringArray(body, "time");
        double[] maxT    = jsonDoubleArray(body, "temperature_2m_max");
        double[] minT    = jsonDoubleArray(body, "temperature_2m_min");
        int[]    codes   = jsonIntArray(body, "weather_code");
        double[] rain    = jsonDoubleArray(body, "precipitation_sum");
        double[] windMax = jsonDoubleArray(body, "wind_speed_10m_max");

        System.out.println();
        System.out.println("┌──────────┬────────────────────┬──────┬──────┬──────────┬──────────┐");
        System.out.println("│ Day      │ Conditions         │ High │ Low  │ Rain     │ Wind max │");
        System.out.println("├──────────┼────────────────────┼──────┼──────┼──────────┼──────────┤");

        for (int i = 0; i < dates.length; i++) {
            LocalDate date  = LocalDate.parse(dates[i]);
            String    label = i == 0 ? "Today   "
                    : date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                      + "  " + String.format("%2d %s", date.getDayOfMonth(),
                            date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));

            String desc = wmoDescription(codes[i]);
            if (desc.length() > 18) desc = desc.substring(0, 17) + "…";

            System.out.printf("│ %-8s │ %-18s │ %3.0f° │ %3.0f° │ %5.1f mm │ %5.1f km/h│%n",
                    label, desc, maxT[i], minT[i], rain[i], windMax[i]);
        }
        System.out.println("└──────────┴────────────────────┴──────┴──────┴──────────┴──────────┘");

        // ── ASCII temperature chart ───────────────────────────────────────────
        System.out.println();
        System.out.println("  Temperature trend  (▲ High  ▼ Low)");
        System.out.println();

        double globalMin = Double.MAX_VALUE, globalMax = -Double.MAX_VALUE;
        for (double v : maxT) globalMax = Math.max(globalMax, v);
        for (double v : minT) globalMin = Math.min(globalMin, v);
        double range = Math.max(globalMax - globalMin, 1);

        int rows = 6;
        for (int row = rows; row >= 0; row--) {
            System.out.printf("  %5.1f° │", globalMin + range * row / rows);
            for (double v : maxT) {
                double norm = (v - globalMin) / range * rows;
                System.out.print(norm >= row - 0.5 ? "  ▲  " : "     ");
            }
            System.out.println();
            System.out.printf("         │");
            for (double v : minT) {
                double norm = (v - globalMin) / range * rows;
                System.out.print(norm >= row - 0.5 ? "  ▼  " : "     ");
            }
            System.out.println();
        }
        System.out.print("         └");
        for (int i = 0; i < dates.length; i++) System.out.print("─────");
        System.out.println();
        System.out.print("          ");
        for (String d : dates) {
            LocalDate date = LocalDate.parse(d);
            System.out.printf(" %-4s ", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
        }
        System.out.println();
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║        Java Weather App  (Open-Meteo)        ║");
        System.out.println("║   Free · No API key · Requires Java 11+      ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        while (true) {
            System.out.print("\nEnter city name (or 'quit' to exit): ");
            String city = sc.nextLine().trim();
            if (city.equalsIgnoreCase("quit") || city.equalsIgnoreCase("q")) break;
            if (city.isEmpty()) continue;

            try {
                double[] coords = geocode(city);
                if (coords == null) {
                    System.out.println("  ⚠ City not found. Please try a different name.");
                    continue;
                }
                fetchAndDisplay(coords[0], coords[1]);
            } catch (Exception e) {
                System.out.println("  ⚠ Error: " + e.getMessage());
            }
        }

        System.out.println("\nGoodbye! 👋");
        sc.close();
    }
}