package org.objectweb.asm.idea.docs;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The instruction reference from chapter 6 of the Java Virtual Machine Specification.
 * <p>
 * It is downloaded from docs.oracle.com on first use and cached, rather than shipped with the plugin, so it is always
 * the text Oracle publishes.
 */
@Service(Service.Level.APP)
public final class JvmSpec{
	
	private static final Logger LOG = Logger.getInstance(JvmSpec.class);
	
	private static final String   URL_PATTERN = "https://docs.oracle.com/javase/specs/jvms/se%d/html/";
	private static final String   PAGE        = "jvms-6.html";
	private static final int      OLDEST      = 21;
	private static final Duration REFRESH     = Duration.ofDays(30);
	private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
	private static final Pattern  CACHED_NAME = Pattern.compile("jvms-6-se(\\d+)\\.html");
	
	/**
	 * The documentation of one instruction, or of a family like {@code if_icmp<cond>}.
	 *
	 * @param title    the mnemonic as the spec writes it
	 * @param url      link to the instruction in the online spec
	 * @param sections section name (Operation, Format, Forms, Operand Stack, Description, Notes...) to its HTML, in order
	 */
	public record Entry(String title, String url, Map<String, String> sections){ }
	
	/**
	 * @param byOpcode keyed by opcode value
	 */
	public record Spec(int version, Map<Integer, Entry> byOpcode){ }
	
	/**
	 * @param page a page of the spec with an optional anchor, like jvms-4.html#jvms-4.7.4
	 */
	public static String url(int version, String page){
		return String.format(URL_PATTERN, version) + page;
	}
	
	private @Nullable CompletableFuture<Spec> spec;
	private           Instant                 failedAt;
	
	/**
	 * Starts loading the spec if needed. Completes exceptionally if it could not be loaded; asking again a little
	 * later retries.
	 */
	public synchronized CompletableFuture<Spec> get(){
		var s = spec;
		if(s != null && (!s.isCompletedExceptionally() || failedAt != null && Instant.now().isBefore(failedAt.plus(RETRY_DELAY)))){
			return s;
		}
		failedAt = null;
		s = CompletableFuture.supplyAsync(this::load, AppExecutorUtil.getAppExecutorService());
		s.whenComplete((r, e) -> {
			if(e != null) synchronized(this){ failedAt = Instant.now(); }
		});
		spec = s;
		return s;
	}
	
	private Spec load(){
		Path dir           = Path.of(PathManager.getSystemPath(), "asm-bytecode-outline");
		Path cached        = findCached(dir);
		int  cachedVersion = cached == null? -1 : version(cached);
		try{
			if(cached != null && Files.getLastModifiedTime(cached).toInstant().plus(REFRESH).isAfter(Instant.now())){
				return parse(cachedVersion, Files.readString(cached));
			}
		}catch(IOException e){
			LOG.debug(e);
		}
		
		IOException failure = null;
		// The newest version is published before the IDE runs on it, so look a bit ahead
		int newest = Math.max(Runtime.version().feature() + 2, cachedVersion + 1);
		for(int v = newest; v>=Math.max(OLDEST, cachedVersion); v--){
			try{
				String html = HttpRequests.request(String.format(URL_PATTERN, v) + PAGE).readString();
				var    spec = parse(v, html);
				if(spec.byOpcode().isEmpty()) continue;
				try{
					Files.createDirectories(dir);
					if(cached != null) Files.deleteIfExists(cached);
					Files.writeString(dir.resolve("jvms-6-se" + v + ".html"), html, StandardCharsets.UTF_8);
				}catch(IOException e){
					LOG.warn("Could not cache the JVM specification", e);
				}
				return spec;
			}catch(HttpRequests.HttpStatusException e){
				// Not published (yet), try an older one
				if(failure == null) failure = e;
			}catch(IOException e){
				failure = e;
				break;
			}
		}
		// Offline: an outdated copy is better than none
		if(cached != null){
			try{
				return parse(cachedVersion, Files.readString(cached));
			}catch(IOException e){
				LOG.debug(e);
			}
		}
		throw new IllegalStateException("Could not download the JVM specification from docs.oracle.com" +
		                                (failure == null? "" : ": " + failure.getMessage()), failure);
	}
	
	private static @Nullable Path findCached(Path dir){
		try(var files = Files.list(dir)){
			return files.filter(f -> CACHED_NAME.matcher(f.getFileName().toString()).matches()).findFirst().orElse(null);
		}catch(IOException e){
			return null;
		}
	}
	
	private static int version(Path cached){
		Matcher m = CACHED_NAME.matcher(cached.getFileName().toString());
		return m.matches()? Integer.parseInt(m.group(1)) : -1;
	}
	
	private static Spec parse(int version, String html){
		return new Spec(version, parse(html, String.format(URL_PATTERN, version)));
	}
	
	// ------------------------------------------------------------ parsing
	
	private static final Pattern INSTRUCTION = Pattern.compile("<div class=\"section-execution\" id=\"(jvms-6\\.5\\.[^\"]+)\">");
	private static final Pattern SECTION     = Pattern.compile("<div class=\"section\" id=\"[^\"]*\">");
	private static final Pattern H3          = Pattern.compile("<h3 class=\"title\">(.*?)</h3>", Pattern.DOTALL);
	private static final Pattern H4          = Pattern.compile("<h4 class=\"title\">(.*?)</h4>\\s*(?:</div>\\s*){3}", Pattern.DOTALL);
	private static final Pattern FORM        = Pattern.compile("([a-z][a-z0-9_]*)\\s*=\\s*(\\d+)\\s*\\(0x");
	private static final Pattern CLOSING     = Pattern.compile("(?:\\s*</div>)+\\s*$");
	private static final Pattern TAG         = Pattern.compile("<[^>]+>");
	
	/**
	 * Splits the instruction chapter into its instructions. Tolerates anything it does not understand by
	 * leaving it out.
	 *
	 * @param baseUrl the directory of the chapter, for making its links absolute
	 */
	static Map<Integer, Entry> parse(String html, String baseUrl){
		Map<Integer, Entry> result = new HashMap<>();
		Matcher             insn   = INSTRUCTION.matcher(html);
		int                 start  = -1;
		String              id     = null;
		while(true){
			boolean found = insn.find();
			if(id != null){
				parseInstruction(html.substring(start, found? insn.start() : html.length()), baseUrl, baseUrl + PAGE + "#" + id, result);
			}
			if(!found) break;
			id = insn.group(1);
			start = insn.end();
		}
		return Collections.unmodifiableMap(result);
	}
	
	private static void parseInstruction(String body, String baseUrl, String url, Map<Integer, Entry> result){
		Matcher title = H3.matcher(body);
		if(!title.find()) return;
		Map<String, String> sections = new LinkedHashMap<>();
		Matcher             section  = SECTION.matcher(body);
		int                 from     = -1;
		while(true){
			boolean found = section.find();
			if(from>=0) addSection(body.substring(from, found? section.start() : body.length()), baseUrl, sections);
			if(!found) break;
			from = section.end();
		}
		String forms = sections.get("Forms");
		if(forms == null) return;
		var     entry = new Entry(unescape(text(title.group(1))), url, Collections.unmodifiableMap(sections));
		Matcher form  = FORM.matcher(unescape(text(forms)));
		while(form.find()) result.putIfAbsent(Integer.parseInt(form.group(2)), entry);
	}
	
	private static void addSection(String chunk, String baseUrl, Map<String, String> sections){
		Matcher h = H4.matcher(chunk);
		if(!h.find()) return;
		String content = CLOSING.matcher(chunk.substring(h.end())).replaceFirst("").strip();
		content = content.replace("href=\"jvms-", "href=\"" + baseUrl + "jvms-")
		                 .replace("href=\"#", "href=\"" + baseUrl + PAGE + "#");
		sections.putIfAbsent(text(h.group(1)).strip(), content);
	}
	
	static String text(String html){
		return TAG.matcher(html).replaceAll("");
	}
	
	private static String unescape(String s){
		return s.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
	}
}
