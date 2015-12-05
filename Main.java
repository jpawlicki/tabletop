import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Main {
	public static void main(String[] args) {
		try {
			new StateServer(new Integer(args[0]), args[1]);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

class StateServer {
	private final int port;
	private HashMap<String, State> states = new HashMap<>();
	private HashMap<String, List<HttpExchange>> clients = new HashMap<>();
	private HashMap<String, SimpleFile> staticFiles = new HashMap<>();
	private Random rand = new Random();
	private Pattern keyPattern = Pattern.compile("-?[0-9a-z]{0,13}");

	public StateServer(int port, String staticFileDir) throws IOException {
		this.port = port;
		HttpServer s = HttpServer.create();
		s.bind(new InetSocketAddress(port), 500);
		s.createContext("/static", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) {
				try {
					handleStatic(exchange);
				} catch (Exception e) {
					e.printStackTrace(); // Otherwise the Executor will silently eat the exception.
				}
		 	}
		});
		s.createContext("/new", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) {
				try {
					handleNew(exchange);
				} catch (Exception e) {
					e.printStackTrace(); // Otherwise the Executor will silently eat the exception.
				}
		 	}
		});
		s.createContext("/view", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) {
				try {
					handleView(exchange);
				} catch (Exception e) {
					e.printStackTrace(); // Otherwise the Executor will silently eat the exception.
				}
		 	}
		});
		s.createContext("/listen", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) {
				try {
					handleListen(exchange);
				} catch (Exception e) {
					e.printStackTrace(); // Otherwise the Executor will silently eat the exception.
				}
		 	}
		});
		s.createContext("/update", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) {
				try {
					handleUpdate(exchange);
				} catch (Exception e) {
					e.printStackTrace(); // Otherwise the Executor will silently eat the exception.
				}
		 	}
		});
		for (File f : new File(staticFileDir).listFiles()) {
			if (f.length() > Integer.MAX_VALUE) {
				System.out.println("Ignoring too-large static file " + f.getName());
			}
			byte[] contents = new byte[(int)(f.length())];
			FileInputStream st = new FileInputStream(f);
			int offset = 0;
			int result = st.read(contents, offset, contents.length - offset);
			while (result != -1 && offset != contents.length) {
				offset += result;
				result = st.read(contents, offset, contents.length - offset);
			}
			st.close();
			staticFiles.put(f.getName(), new SimpleFile(getMimeType(f.getName()), contents));
			System.out.println("Loaded " + f.getName());
		}
		s.start();
	}

	private void handleStatic(HttpExchange exchange) {
		String path = exchange.getRequestURI().getPath().substring("/static/".length());
		SimpleFile file = staticFiles.get(path);
		if (file != null) {
			serve200(exchange, file.content, file.mimeType);
		} else {
			serve404(exchange);
		}
	}

	private void handleView(HttpExchange exchange) {
		String request = exchange.getRequestURI().getPath().substring(1);
		String file = request.substring(0, request.lastIndexOf("/"));
		String key = request.substring(file.length() + 1);
		file += ".html";
		if (keyPattern.matcher(key).matches() && !file.contains("/")) {
			String viewPage = new String(staticFiles.get(file).content);
			serve200(exchange, viewPage.replace("%%pagekey%%", key).getBytes(), "text/html");
		} else {
			serve404(exchange);
		}
	}

	private void handleNew(HttpExchange exchange) {
		serve303(exchange, "view/" + Long.toString(rand.nextLong(), 36));
	}

	private synchronized void handleUpdate(HttpExchange exchange) {
		String path = exchange.getRequestURI().getPath().substring("/update/".length());
		if (keyPattern.matcher(path).matches()) {
			// Handle state update.
			JsonObject data = Json.createReader(exchange.getRequestBody()).readObject();
			System.out.println(data.toString());
			switch (data.getString("type")) {
				case "mpush":
					updateMarker(path, data);
					break;
				case "mdel":
					deleteMarker(path, data);
					break;
				case "bgimage":
					setBgImage(path, data);
					break;
				case "cpush":
					updateCharacter(path, data);
					break;
			}
			serve200(exchange, "200 OK".getBytes(), "text/plain");
			// Notify listeners.
			byte[] b = states.get(path).getBytes();
			List<HttpExchange> clientList = clients.get(path);
			if (clientList != null) {
				for (HttpExchange e : clientList) {
					serve200(e, b, "application/json");
				}
				clients.remove(path);
			}
		} else {
			serve404(exchange);
		}
	}

	private void updateMarker(String path, JsonObject data) {
		State s = getState(path);
		states.put(path, new State.Builder(s)
				.setVersion(s.version + 1)
				.updateMarker(data.getString("id"), new Marker.Builder()
						.setPosx(data.getInt("posx"))
						.setPosy(data.getInt("posy"))
						.setShape(data.getInt("shape"))
						.setColor(data.getString("color"))
						.setLabel(data.getString("label"))
						.setRotation(data.getJsonNumber("rotation").doubleValue())
						.setSize(data.getJsonNumber("size").doubleValue())
						.build()).build());
	}

	private void updateCharacter(String path, JsonObject data) {
		State s = getState(path);
		states.put(path, new State.Builder(s)
				.setVersion(s.version + 1)
				.updateCharacter(data.getInt("id"), new Character.Builder()
						.setName(data.getString("name"))
						.setStats(toIntArray(data.getJsonArray("stats")))
						.setRolls(toIntArray(data.getJsonArray("rolls")))
						.setFatigues(toIntArray(data.getJsonArray("fatigues")))
						.setSkills(toStringArray(data.getJsonArray("skills")))
						.setLegalSkills(toStringArray(data.getJsonArray("legalskills")))
						.setWeapon(data.getString("weapon"))
						.setLegalWeapons(toStringArray(data.getJsonArray("legalweapons")))
						.setSpirit(data.getInt("spirit"))
						.build()).build());
	}

	private void deleteMarker(String path, JsonObject data) {
		State s = getState(path);
		states.put(path, new State.Builder(s).setVersion(s.version + 1).deleteMarker(data.getString("id")).build());
	}

	private void setBgImage(String path, JsonObject data) {
		State s = getState(path);
		states.put(path, new State.Builder(s).setVersion(s.version + 1).setBgImage(data.getString("bgimage")).build());
	}

	private synchronized void handleListen(HttpExchange exchange) {
		String path = exchange.getRequestURI().getPath().substring("/listen/".length());
		if (keyPattern.matcher(path).matches()) {
			try {
				int version = new Integer(exchange.getRequestURI().getQuery().substring("p=".length()));
				State s = getState(path);
				if (s.version > version) {
					serve200(exchange, s.getBytes(), "application/json");
				} else {
					List<HttpExchange> listeners = clients.get(path);
					if (listeners == null) {
						listeners = new ArrayList<HttpExchange>();
						clients.put(path, listeners);
					}
					listeners.add(exchange);
				}
			} catch (NumberFormatException e) {
				serve404(exchange);
			}
		} else {
			serve404(exchange);
		}
	}

	private void serve404(HttpExchange exchange) {
		try {
			exchange.sendResponseHeaders(404, 0);
			exchange.getResponseBody().close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void serve303(HttpExchange exchange, String loc) {
		try {
			exchange.getResponseHeaders().add("Location", loc);
			exchange.sendResponseHeaders(303, 0);
			exchange.getResponseBody().close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void serve200(HttpExchange exchange, byte[] resp, String mime) {
		try {
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseHeaders().add("Content-Type", mime);
			try (OutputStream o = exchange.getResponseBody()) {
				o.write(resp);
				o.flush();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private String getMimeType(String file) {
		if (file.endsWith(".html")) {
		 	return "text/html";
		} else if (file.endsWith(".png")) {
			return "image/png";
		} else {
			return "unknown";
		}
	}

	private synchronized State getState(String key) {
		State s = states.get(key);
		if (s == null) {
			s = new State.Builder().build();
			states.put(key, s);
		}
		return s;
	}

	private int[] toIntArray(JsonArray a) {
		int[] s = new int[a.size()];
		for (int i = 0; i < s.length; i++) {
			s[i] = a.getInt(i);
		}
		return s;
	}

	private String[] toStringArray(JsonArray a) {
		String[] s = new String[a.size()];
		for (int i = 0; i < s.length; i++) {
			s[i] = a.getString(i);
		}
		return s;
	}
}

final class State {
	public final int version;
	public final String bgimage;
	public final HashMap<String, Marker> markers;
	public final ArrayList<Character> characters;

	public byte[] getBytes() {
		JsonObjectBuilder markersBuilder = Json.createObjectBuilder();
		JsonArrayBuilder charactersBuilder = Json.createArrayBuilder();
		for (Map.Entry<String, Marker> e : markers.entrySet()) {
			Marker m = e.getValue();
			markersBuilder.add(e.getKey(), Json.createObjectBuilder()
					.add("posx", m.posx)
					.add("posy", m.posy)
					.add("shape", m.shape)
					.add("color", m.color)
					.add("size", m.size)
					.add("rotation", m.rotation)
					.add("label", m.label)
					.build());
		}
		for (Character c : characters) {
			JsonArrayBuilder statBuilder = Json.createArrayBuilder();
			for (int v : c.stats) {
				statBuilder.add(v);
			}
			JsonArrayBuilder rollBuilder = Json.createArrayBuilder();
			for (int v : c.rolls) {
				rollBuilder.add(v);
			}
			JsonArrayBuilder fatigueBuilder = Json.createArrayBuilder();
			for (int v : c.fatigues) {
				fatigueBuilder.add(v);
			}
			JsonArrayBuilder skillBuilder = Json.createArrayBuilder();
			for (String v : c.skills) {
				skillBuilder.add(v);
			}
			JsonArrayBuilder legalSkillBuilder = Json.createArrayBuilder();
			for (String v : c.legalSkills) {
				legalSkillBuilder.add(v);
			}
			JsonArrayBuilder legalWeaponsBuilder = Json.createArrayBuilder();
			for (String v : c.legalWeapons) {
				legalWeaponsBuilder.add(v);
			}
			charactersBuilder.add(Json.createObjectBuilder()
					.add("name", c.name)
					.add("weapon", c.weapon)
					.add("stats", statBuilder.build())
					.add("rolls", rollBuilder.build())
					.add("fatigues", fatigueBuilder.build())
					.add("skills", skillBuilder.build())
					.add("legalskills", legalSkillBuilder.build())
					.add("legalweapons", legalWeaponsBuilder.build())
					.add("spirit", c.spirit)
					.build());
		}

		return Json.createObjectBuilder()
				.add("version", version)
				.add("markers", markersBuilder.build())
				.add("characters", charactersBuilder.build())
				.add("bgimage", bgimage)
				.build().toString().getBytes();
	}

	private State(
			int version,
			String bgimage,
			HashMap<String, Marker> markers,
			ArrayList<Character> characters) {
		this.version = version;
		this.bgimage = bgimage;
		this.markers = markers;
		this.characters = characters;
	}

	// A mutable builder for an immutable State.
	static class Builder {
		private int version = 1;
		private String bgimage = "";
		private HashMap<String, Marker> markers = new HashMap<>();
		private ArrayList<Character> characters = new ArrayList<>();

		public Builder() {
		}

		public Builder(State s) {
			version = s.version;
			bgimage = s.bgimage;
			for (Map.Entry<String, Marker> e : s.markers.entrySet()) {
				markers.put(e.getKey(), e.getValue());
			}
			for (Character c : s.characters) {
				characters.add(c);
			}
		}

		public State build() {
			return new State(version, bgimage, markers, characters);
		}

		public Builder setVersion(int version) {
			this.version = version;
			return this;
		}

		public Builder setBgImage(String bgimage) {
			this.bgimage = bgimage;
			return this;
		}

		public Builder updateMarker(String id, Marker m) {
			markers.put(id, m);
			return this;
		}

		public Builder updateCharacter(int i, Character c) {
			if (i >= characters.size()) {
				characters.add(c);
			} else {
				characters.set(i, c);
			}
			return this;
		}

		public Builder deleteMarker(String id) {
			markers.remove(id);
			return this;
		}
	}
}

final class Marker {
	public final int posx;
	public final int posy;
	public final int shape;
	public final String color;
	public final double size;
	public final double rotation;
	public final String label;

	private Marker(int posx, int posy, int shape, String color, double size, double rotation, String label) {
		this.posx = posx;
		this.posy = posy;
		this.shape = shape;
		this.color = color;
		this.size = size;
		this.rotation = rotation;
		this.label = label;
	}

	static class Builder {
		private int posx;
		private int posy;
		private int shape;
		private String color;
		private double size;
		private double rotation;
		private String label;

		public Builder() {
		}

		public Builder(Marker m) {
			posx = m.posx;
			posy = m.posy;
			shape = m.shape;
			color = m.color;
			size = m.size;
			rotation = m.rotation;
			label = m.label;
		}

		public Marker build() {
			return new Marker(posx, posy, shape, color, size, rotation, label);
		}

		public Builder setPosx(int posx) {
			this.posx = posx;
			return this;
		}

		public Builder setPosy(int posy) {
			this.posy = posy;
			return this;
		}

		public Builder setShape(int shape) {
			this.shape = shape;
			return this;
		}

		public Builder setColor(String color) {
			this.color = color;
			return this;
		}

		public Builder setSize(double size) {
			this.size = size;
			return this;
		}

		public Builder setRotation(double rotation) {
			this.rotation = rotation;
			return this;
		}

		public Builder setLabel(String label) {
			this.label = label;
			return this;
		}
	}
}

final class Character {
	public final String name;
	public final int[] stats;
	public final int[] rolls;
	public final int[] fatigues;
	public final String[] skills;
	public final String[] legalSkills;
	public final String[] legalWeapons;
	public final String weapon;
	public final int spirit;

	private Character(
			String name,
			int[] stats,
			int[] rolls,
			int[] fatigues,
			String[] skills,
			String weapon,
			String[] legalSkills,
			String[] legalWeapons,
			int spirit) {
		this.name = name;
		this.stats = stats;
		this.rolls = rolls;
		this.fatigues = fatigues;
		this.skills = skills;
		this.weapon = weapon;
		this.legalSkills = legalSkills;
		this.legalWeapons = legalWeapons;
		this.spirit = spirit;
	}

	public static class Builder {
		private String name;
		private int[] stats;
		private int[] rolls;
		private int[] fatigues;
		private String[] skills;
		private String weapon;
		private String[] legalSkills;
		private String[] legalWeapons;
		private int spirit;

		public Builder() {
		}
		public Builder(Builder o) {
			this.name = o.name;
			this.stats = o.stats;
			this.rolls = o.rolls;
			this.fatigues = o.fatigues;
			this.skills = o.skills;
			this.weapon = o.weapon;
			this.legalSkills = o.legalSkills;
			this.legalWeapons = o.legalWeapons;
			this.spirit = o.spirit;
		}
		public Builder setName(String name) {
			this.name = name;
			return this;
		}
		public Builder setStats(int[] stats) {
			this.stats = stats;
			return this;
		}
		public Builder setRolls(int[] rolls) {
			this.rolls = rolls;
			return this;
		}
		public Builder setFatigues(int[] fatigues) {
			this.fatigues = fatigues;
			return this;
		}
		public Builder setSkills(String[] skills) {
			this.skills = skills;
			return this;
		}
		public Builder setWeapon(String weapon) {
			this.weapon = weapon;
			return this;
		}
		public Builder setSpirit(int spirit) {
			this.spirit = spirit;
			return this;
		}
		public Builder setLegalSkills(String[] legalSkills) {
			this.legalSkills = legalSkills;
			return this;
		}
		public Builder setLegalWeapons(String[] legalWeapons) {
			this.legalWeapons = legalWeapons;
			return this;
		}
		public Character build() {
			return new Character(
					name,
					stats,
					rolls,
					fatigues,
					skills,
					weapon,
					legalSkills,
					legalWeapons,
					spirit);
		}
	}
}

final class SimpleFile {
	public final String mimeType;
	public final byte[] content;

	public SimpleFile(String mimeType, byte[] content) {
		this.mimeType = mimeType;
		this.content = content;
	}
}
