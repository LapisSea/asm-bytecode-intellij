package org.objectweb.asm.idea.docs;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.idea.sync.RecordingTextifier.Frame;
import org.objectweb.asm.idea.sync.RecordingTextifier.Info;
import org.objectweb.asm.idea.sync.RecordingTextifier.Insn;
import org.objectweb.asm.idea.sync.RecordingTextifier.LabelInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.LineInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.MaxsInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.Printed;
import org.objectweb.asm.idea.sync.RecordingTextifier.TryCatchInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.VarInfo;
import org.objectweb.asm.util.Printer;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Shows the specification of the selected instruction, and what it does with its operands.
 */
public final class InsnDocPanel extends JPanel implements Disposable{
	
	/**
	 * Byte encoding details, of no use when reading ASM's output
	 */
	private static final Set<String> HIDDEN_SECTIONS = Set.of("Operation", "Format", "Forms");
	
	private final Project     project;
	private final JEditorPane pane = new JEditorPane();
	
	private @Nullable Printed item;
	private @Nullable String  explanation;
	private           int     generation;
	
	public InsnDocPanel(Project project){
		super(new BorderLayout());
		this.project = project;
		pane.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
		pane.setEditable(false);
		pane.setBorder(JBUI.Borders.empty(6, 8));
		pane.addHyperlinkListener(e -> {
			if(e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) BrowserUtil.browse(e.getURL());
		});
		add(new JBScrollPane(pane), BorderLayout.CENTER);
		render();
	}
	
	/**
	 * @param item the instruction or frame under the caret, or null if it is not on one
	 */
	public void show(@Nullable Printed item){
		if(item == this.item) return;
		this.item = item;
		int gen = ++generation;
		explanation = switch(item){
			case null -> null;
			case Insn insn -> InsnExplainer.explain(insn, InsnExplainer.Resolver.NONE);
			case Frame frame -> InsnExplainer.explainFrame(frame);
			case Info info -> InsnExplainer.explainInfo(info);
		};
		render();
		if(!(item instanceof Insn insn)) return;
		
		// Names and generics from the source, once indexes allow
		ReadAction.nonBlocking(() -> InsnExplainer.explain(insn, new PsiResolver(project)))
		          .inSmartMode(project)
		          .expireWith(this)
		          .finishOnUiThread(ModalityState.any(), html -> {
			          if(gen != generation) return;
			          explanation = html;
			          render();
		          })
		          .submit(AppExecutorUtil.getAppExecutorService());
		
		var spec = ApplicationManager.getApplication().getService(JvmSpec.class).get();
		if(!spec.isDone()){
			spec.whenComplete((s, e) -> ApplicationManager.getApplication().invokeLater(() -> {
				if(gen == generation) render();
			}, ModalityState.any()));
		}
	}
	
	private void render(){
		var html = new StringBuilder("<html><head><style>").append(styles()).append("</style></head><body>");
		var item = this.item;
		if(item == null){
			html.append("<p class=\"muted\">Put the caret on an instruction to see what it does.</p>");
		}else if(item instanceof Frame){
			html.append("<h2>FRAME</h2>");
			if(explanation != null) html.append("<div class=\"explain\">").append(explanation).append("</div>");
			var spec = ApplicationManager.getApplication().getService(JvmSpec.class).get();
			if(spec.isDone() && !spec.isCompletedExceptionally()){
				html.append("<p class=\"muted\"><a href=\"").append(JvmSpec.url(spec.join().version(), "jvms-4.html#jvms-4.10.1"))
				    .append("\">Verification by type checking</a> and <a href=\"").append(JvmSpec.url(spec.join().version(), "jvms-4.html#jvms-4.7.4"))
				    .append("\">the StackMapTable attribute</a> in the Java Virtual Machine Specification, Java SE ").append(spec.join().version()).append("</p>");
			}
		}else if(item instanceof Info info){
			html.append("<h2>").append(switch(info){
				case LabelInfo l -> "Label";
				case LineInfo l -> "LINENUMBER";
				case VarInfo v -> "LOCALVARIABLE";
				case TryCatchInfo t -> "TRYCATCHBLOCK";
				case MaxsInfo m -> "MAXSTACK, MAXLOCALS";
			}).append("</h2>");
			html.append("<div class=\"explain\">").append(explanation).append("</div>");
		}else if(item instanceof Insn insn){
			String        opName = Printer.OPCODES[insn.opcode()];
			var           spec   = ApplicationManager.getApplication().getService(JvmSpec.class).get();
			JvmSpec.Entry entry  = spec.isDone() && !spec.isCompletedExceptionally()? spec.join().byOpcode().get(insn.opcode()) : null;
			
			html.append("<h2>").append(opName).append("</h2>");
			if(entry != null && entry.sections().containsKey("Operation")){
				html.append("<div>").append(entry.sections().get("Operation")).append("</div>");
			}
			html.append("<p class=\"muted\">Opcode ").append(insn.opcode()).append(" (0x").append(Integer.toHexString(insn.opcode())).append(")</p>");
			if(explanation != null) html.append("<div class=\"explain\">").append(explanation).append("</div>");
			
			if(entry != null){
				entry.sections().forEach((name, content) -> {
					if(HIDDEN_SECTIONS.contains(name)) return;
					html.append("<h3>").append(InsnExplainer.esc(name)).append("</h3>").append(content);
				});
				html.append("<p class=\"muted\"><a href=\"").append(entry.url()).append("\">").append(InsnExplainer.esc(entry.title()))
				    .append(" in the Java Virtual Machine Specification, Java SE ").append(spec.join().version()).append("</a></p>");
			}else if(!spec.isDone()){
				html.append("<p class=\"muted\">Loading the instruction reference from docs.oracle.com…</p>");
			}else if(spec.isCompletedExceptionally()){
				html.append("<p class=\"muted\">").append(InsnExplainer.esc(failure(spec))).append("</p>");
			}
		}
		html.append("</body></html>");
		pane.setText(html.toString());
		pane.setCaretPosition(0);
	}
	
	/**
	 * Built on every render so a theme change is picked up
	 */
	private static String styles(){
		String grey    = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground());
		String explain = ColorUtil.toHtmlColor(ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.07));
		return ".pkg, .muted { color: " + grey + "; }" +
		       "h2 { margin-top: 0; margin-bottom: 2px; }" +
		       "h3 { margin-top: 10px; margin-bottom: 2px; }" +
		       "p { margin-top: 3px; margin-bottom: 3px; }" +
		       ".explain { background-color: " + explain + "; padding: 4px 8px; margin-top: 6px; margin-bottom: 4px; }";
	}
	
	private static String failure(CompletableFuture<?> f){
		try{
			f.join();
			return "";
		}catch(CompletionException e){
			var cause = e.getCause() != null? e.getCause() : e;
			return cause.getMessage() == null? cause.toString() : cause.getMessage();
		}
	}
	
	@Override
	public void dispose(){ }
}
