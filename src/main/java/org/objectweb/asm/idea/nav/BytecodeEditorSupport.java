package org.objectweb.asm.idea.nav;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.idea.highlight.BytecodeReference;
import org.objectweb.asm.idea.sync.RecordingTextifier;

import javax.swing.*;
import java.awt.Cursor;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Makes the bytecode editor behave more like a code editor: Ctrl+click and Go to Declaration on class, method,
 * field and label names, usages of what is under the caret (highlighted, and listed with Find Usages), and folding
 * of methods.
 */
public final class BytecodeEditorSupport implements Disposable{
	
	private final     Project                       project;
	private final     Editor                        editor;
	private final     Function<String, Navigatable> lambdaSource;
	private final     List<RangeHighlighter>        usageHighlights = new ArrayList<>();
	private @Nullable RecordingTextifier.Result     printed;
	private @Nullable RangeHighlighter              link;
	private final     KeyAdapter                    keyListener;
	
	/**
	 * @param lambdaSource finds the source of a lambda by its synthetic method name, or returns null
	 */
	public BytecodeEditorSupport(Project project, Editor editor, Function<String, Navigatable> lambdaSource){
		this.project = project;
		this.editor = editor;
		this.lambdaSource = lambdaSource;
		
		editor.addEditorMouseMotionListener(new EditorMouseMotionListener(){
			@Override
			public void mouseMoved(@NotNull EditorMouseEvent e){
				updateLink(e);
			}
		}, this);
		editor.addEditorMouseListener(new EditorMouseListener(){
			@Override
			public void mouseClicked(@NotNull EditorMouseEvent e){
				MouseEvent me = e.getMouseEvent();
				if(me.getButton() != MouseEvent.BUTTON1 || !isLinkModifier(me) || !e.isOverText()) return;
				e.consume();
				clearLink();
				navigate(e.getOffset());
			}
			
			@Override
			public void mouseExited(@NotNull EditorMouseEvent e){
				clearLink();
			}
		}, this);
		keyListener = new KeyAdapter(){
			@Override
			public void keyReleased(KeyEvent e){
				if(!(SystemInfo.isMac? e.isMetaDown() : e.isControlDown())) clearLink();
			}
		};
		editor.getContentComponent().addKeyListener(keyListener);
		editor.getCaretModel().addCaretListener(new CaretListener(){
			@Override
			public void caretPositionChanged(@NotNull CaretEvent event){
				highlightUsages();
			}
		}, this);
		
		// Go to Declaration (Ctrl+B and whatever the keymap says) works on the caret
		new DumbAwareAction(){
			@Override
			public void actionPerformed(@NotNull AnActionEvent e){
				navigate(editor.getCaretModel().getOffset());
			}
			
			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread(){
				return ActionUpdateThread.EDT;
			}
		}.registerCustomShortcutSet(ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION).getShortcutSet(),
		                            editor.getContentComponent(), this);
		
		// Find Usages and Show Usages (Alt+F7, Ctrl+Alt+F7) list usages in this class
		for(String id : List.of(IdeActions.ACTION_FIND_USAGES, "ShowUsages")){
			var action = ActionManager.getInstance().getAction(id);
			if(action == null) continue;
			new DumbAwareAction(){
				@Override
				public void actionPerformed(@NotNull AnActionEvent e){
					showUsagesAtCaret();
				}
				
				@Override
				public @NotNull ActionUpdateThread getActionUpdateThread(){
					return ActionUpdateThread.EDT;
				}
			}.registerCustomShortcutSet(action.getShortcutSet(), editor.getContentComponent(), this);
		}
	}
	
	private static boolean isLinkModifier(MouseEvent e){
		return SystemInfo.isMac? e.isMetaDown() : e.isControlDown();
	}
	
	// ------------------------------------------------------------ navigation
	
	private @Nullable BytecodeReference referenceAt(int offset){
		var text = editor.getDocument().getImmutableCharSequence();
		return BytecodeReference.at(text, offset, BytecodeReference.declaredClass(text));
	}
	
	private void navigate(int offset){
		var ref = referenceAt(offset);
		if(ref == null) return;
		var target = target(ref, offset);
		if(target != null){
			target.navigate(true);
		}else if(ref.kind() != BytecodeReference.Kind.LABEL){
			HintManager.getInstance().showErrorHint(editor, DumbService.isDumb(project)?
			                                                "Navigation is not available while indexing" :
			                                                "Cannot find the declaration of " + ref.owner().replace('/', '.') +
			                                                (ref.name() == null? "" : "." + ref.name()));
		}
	}
	
	private @Nullable Navigatable target(BytecodeReference ref, int offset){
		if(ref.kind() == BytecodeReference.Kind.LABEL){
			var def = BytecodeReference.labelDefinition(editor.getDocument().getImmutableCharSequence(), offset, ref.name());
			if(def == null || def.containsOffset(offset)) return null;
			return new Navigatable(){
				@Override
				public void navigate(boolean requestFocus){
					editor.getCaretModel().moveToOffset(def.getStartOffset());
					editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
				}
				
				@Override
				public boolean canNavigate(){ return true; }
				
				@Override
				public boolean canNavigateToSource(){ return false; }
			};
		}
		if(ref.kind() == BytecodeReference.Kind.METHOD && ref.name().startsWith("lambda$")){
			var lambda = lambdaSource.apply(ref.name());
			if(lambda != null) return lambda;
		}
		if(DumbService.isDumb(project)) return null;
		return ApplicationManager.getApplication().runReadAction((Computable<Navigatable>)() -> {
			PsiElement e = DeclarationResolver.resolve(project, ref);
			if(e == null) return null;
			var nav = e.getNavigationElement();
			return nav instanceof Navigatable n && n.canNavigate()? n : e instanceof Navigatable n && n.canNavigate()? n : null;
		});
	}
	
	// ------------------------------------------------------------ Ctrl+hover link
	
	private void updateLink(EditorMouseEvent e){
		if(!isLinkModifier(e.getMouseEvent()) || !e.isOverText()){
			clearLink();
			return;
		}
		int offset = e.getOffset();
		var ref    = referenceAt(offset);
		if(ref == null){
			clearLink();
			return;
		}
		if(link != null && link.isValid() && link.getStartOffset() == ref.range().getStartOffset() && link.getEndOffset() == ref.range().getEndOffset())
			return;
		clearLink();
		if(target(ref, offset) == null) return;
		link = editor.getMarkupModel().addRangeHighlighter(EditorColors.REFERENCE_HYPERLINK_COLOR, ref.range().getStartOffset(), ref.range().getEndOffset(),
		                                                   HighlighterLayer.HYPERLINK, HighlighterTargetArea.EXACT_RANGE);
		if(editor instanceof EditorEx ex) ex.setCustomCursor(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}
	
	private void clearLink(){
		if(link == null) return;
		editor.getMarkupModel().removeHighlighter(link);
		link = null;
		if(editor instanceof EditorEx ex) ex.setCustomCursor(this, null);
	}
	
	// ------------------------------------------------------------ usages
	
	private @Nullable BytecodeUsages.Symbol symbolAtCaret(){
		return BytecodeUsages.at(editor.getDocument().getImmutableCharSequence(), printed, editor.getCaretModel().getOffset());
	}
	
	/**
	 * Highlights the other usages of what is under the caret, writes differently from reads
	 */
	private void highlightUsages(){
		for(var h : usageHighlights) editor.getMarkupModel().removeHighlighter(h);
		usageHighlights.clear();
		var symbol = symbolAtCaret();
		if(symbol == null) return;
		var usages = BytecodeUsages.find(editor.getDocument().getImmutableCharSequence(), symbol, String::equals);
		if(usages.size()<2) return;
		for(var u : usages){
			var key = u.write()? EditorColors.WRITE_IDENTIFIER_UNDER_CARET_ATTRIBUTES : EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES;
			usageHighlights.add(editor.getMarkupModel().addRangeHighlighter(key, u.range().getStartOffset(), u.range().getEndOffset(),
			                                                                HighlighterLayer.SELECTION - 1, HighlighterTargetArea.EXACT_RANGE));
		}
	}
	
	private void showUsagesAtCaret(){
		var symbol = symbolAtCaret();
		if(symbol == null){
			HintManager.getInstance().showInformationHint(editor, "Put the caret on a class, method, field, label or local variable");
			return;
		}
		showUsages(symbol, String::equals, null);
	}
	
	/**
	 * Lists the usages in a popup over the bytecode, picking one goes there.
	 *
	 * @param sameOwner see {@link BytecodeUsages#find}
	 * @param from      where the search was started, for the message if there are none; null for the bytecode
	 */
	public void showUsages(BytecodeUsages.Symbol symbol, BiPredicate<String, String> sameOwner, @Nullable Editor from){
		var    text   = editor.getDocument().getImmutableCharSequence();
		var    usages = BytecodeUsages.find(text, symbol, sameOwner);
		String cls    = BytecodeReference.declaredClass(text);
		String where  = cls == null? "the bytecode" : "the bytecode of " + cls.substring(cls.lastIndexOf('/') + 1).replace('$', '.');
		if(usages.isEmpty()){
			HintManager.getInstance().showInformationHint(from != null? from : editor, "No usages of " + symbol.displayName() + " in " + where);
			return;
		}
		record Item(BytecodeUsages.Usage usage, String context, String line, int lineStart){ }
		var items = new ArrayList<Item>();
		for(var u : usages){
			var    m       = BytecodeUsages.methodAt(printed, u.line());
			String context = m == null? "" : m.name.equals("<init>")? "constructor" : m.name.equals("<clinit>")? "static initializer" : m.name;
			int    start   = editor.getDocument().getLineStartOffset(u.line());
			items.add(new Item(u, context, editor.getDocument().getText(new TextRange(start, editor.getDocument().getLineEndOffset(u.line()))), start));
		}
		JBPopupFactory.getInstance()
		              .createPopupChooserBuilder(items)
		              .setTitle(usages.size() + (usages.size() == 1? " usage of " : " usages of ") + symbol.displayName() + " in " + where)
		              .setRenderer(new ColoredListCellRenderer<Item>(){
			              @Override
			              protected void customizeCellRenderer(@NotNull JList<? extends Item> list, Item item, int index, boolean selected, boolean hasFocus){
				              String                                                           line                                                            = item.line;
				              int from = item.usage.range().getStartOffset() - item.lineStart, to = item.usage.range().getEndOffset() - item.lineStart;
				              int                                                              lead                                                            = 0;
				              while(lead<line.length() && line.charAt(lead) == ' ') lead++;
				              append(String.valueOf(item.usage.line() + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				              append("  ");
				              append(line.substring(lead, Math.max(lead, from)));
				              append(line.substring(from, to), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
				              append(line.substring(to));
				              if(item.usage.declaration()) append("  declaration", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
				              else if(item.usage.write()) append("  write", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
				              if(!item.context.isEmpty()) append("  in " + item.context, SimpleTextAttributes.GRAYED_ATTRIBUTES);
			              }
		              })
		              .setItemChosenCallback(item -> {
			              editor.getCaretModel().moveToOffset(item.usage.range().getStartOffset());
			              editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
			              IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
		              })
		              .createPopup()
		              .showInBestPositionFor(editor);
	}
	
	// ------------------------------------------------------------ folding
	
	/**
	 * Replaces the fold regions after the text changed: method bodies, and multi-line instructions such as
	 * invokedynamic with its bootstrap arguments.
	 */
	public void updateFolding(@Nullable RecordingTextifier.Result printed){
		this.printed = printed;
		// The text was replaced, old highlights point nowhere
		for(var h : usageHighlights) editor.getMarkupModel().removeHighlighter(h);
		usageHighlights.clear();
		var doc = editor.getDocument();
		var fm  = editor.getFoldingModel();
		fm.runBatchFoldingOperation(() -> {
			for(var r : fm.getAllFoldRegions()) fm.removeFoldRegion(r);
			if(printed == null) return;
			int lines = doc.getLineCount();
			for(var m : printed.methods()){
				int first = Integer.MAX_VALUE, last = -1;
				for(var list : List.of(m.insns, m.frames, m.infos)){
					for(var p : list){
						first = Math.min(first, p.textStart());
						last = Math.max(last, p.textEnd());
					}
				}
				if(last<0 || first<1 || last>=lines) continue;
				fold(doc.getLineEndOffset(first - 1), doc.getLineEndOffset(last), " ...");
				
				for(var insn : m.insns){
					boolean multiLine = insn.opcode() == Opcodes.INVOKEDYNAMIC || insn.opcode() == Opcodes.TABLESWITCH || insn.opcode() == Opcodes.LOOKUPSWITCH;
					if(!multiLine || insn.textEnd()<=insn.textStart() || insn.textEnd()>=lines) continue;
					int start = doc.getLineEndOffset(insn.textStart());
					// Keep the [ visible: INVOKEDYNAMIC name(...)... [...]
					if(start>0 && doc.getCharsSequence().charAt(start - 1) == '[') start--;
					fold(start, doc.getLineEndOffset(insn.textEnd()), insn.opcode() == Opcodes.INVOKEDYNAMIC? "[...]" : " ...");
				}
			}
		});
	}
	
	private void fold(int start, int end, String placeholder){
		if(end - start<2) return;
		editor.getFoldingModel().addFoldRegion(start, end, placeholder);
	}
	
	@Override
	public void dispose(){
		editor.getContentComponent().removeKeyListener(keyListener);
		clearLink();
	}
}
