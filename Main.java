import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import javax.json.Json;
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
				handleUpdate(exchange);
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
		String path = exchange.getRequestURI().getPath().substring("/view/".length());
		if (keyPattern.matcher(path).matches()) {
			String viewPage = new String(staticFiles.get("view.html").content);
			serve200(exchange, viewPage.replace("%%pagekey%%", path).getBytes(), "text/html");
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
			State s = getState(path);
			State nu = new State.Builder(s).setVersion(s.version + 1).setDebug(rand.nextLong()).build();
			states.put(path, nu);
			// Notify listeners.
			for (HttpExchange e : clients.get(path)) {
				serve200(e, nu.getBytes(), "application/json");
			}
			clients.remove(path);
			serve200(exchange, new byte[0], "text/html");
		} else {
			serve404(exchange);
		}
	}

	private synchronized void handleListen(HttpExchange exchange) {
		String path = exchange.getRequestURI().getPath().substring("/listen/".length());
		if (keyPattern.matcher(path).matches()) {
			try {
				int version = new Integer(exchange.getRequestURI().getQuery().substring("p=".length()));
				System.out.println(version);
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
}

final class State {
	public final int version;
	public final long debug;

	public byte[] getBytes() {
		return Json.createObjectBuilder()
				.add("version", version)
				.add("debug", debug)
				.build().toString().getBytes();
	}

	private State(int version, long debug) {
		this.version = version;
		this.debug = debug;
	}

	// A mutable builder for an immutable State.
	static class Builder {
		private int version = 1;
		private long debug = 0;

		public Builder() {
		}

		public Builder(State s) {
			version = s.version;
			debug = s.debug;
		}

		public State build() {
			return new State(version, debug);
		}

		public Builder setVersion(int version) {
			this.version = version;
			return this;
		}

		public Builder setDebug(long debug) {
			this.debug = debug;
			return this;
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
