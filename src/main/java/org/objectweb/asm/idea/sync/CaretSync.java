package org.objectweb.asm.idea.sync;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Keeps the bytecode view and the source editor(s) of the class it was generated from in sync:
 * moving the caret in the source highlights the bytecode generated from that line, and moving
 * the caret in the bytecode highlights the source line it came from.
 * <p>
 * Only highlights and scrolls the other side, it never moves its caret, so the two directions cannot feed back into each other.
 */
public final class CaretSync implements Disposable{

	public static final TextAttributesKey SYNC_LINE =
		TextAttributesKey.createTextAttributesKey("ASM_BYTECODE_SYNC_LINE", EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES);

	private final Project         project;
	private final Editor          bytecodeEditor;
	private final BooleanSupplier enabled;

	private @Nullable VirtualFile sourceFile;
	private           LineMapping mapping = LineMapping.EMPTY;
	private           boolean     updating;

	private final List<RangeHighlighter> bytecodeHighlights = new ArrayList<>();
	private final List<RangeHighlighter> sourceHighlights   = new ArrayList<>();

	public CaretSync(Project project, Editor bytecodeEditor, BooleanSupplier enabled){
		this.project = project;
		this.bytecodeEditor = bytecodeEditor;
		this.enabled = enabled;
		EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener(){
			@Override
			public void caretPositionChanged(@NotNull CaretEvent event){
				onCaretMoved(event.getEditor());
			}
		}, this);
	}

	/**
	 * Replaces the bytecode text while keeping the mapping consistent with it.
	 *
	 * @param sourceFile   the file the class was compiled from, or null if unknown
	 * @param setText      performs the actual document update
	 * @param bytecodeText the new bytecode text
	 */
	public void update(@Nullable VirtualFile sourceFile, Runnable setText, String bytecodeText){
		clearHighlights();
		updating = true;
		try{
			mapping = LineMapping.EMPTY;
			setText.run();
		}finally{
			updating = false;
		}
		this.sourceFile = sourceFile;
		mapping = sourceFile == null? LineMapping.EMPTY : LineMapping.parse(bytecodeText);
	}

	/**
	 * Highlights the bytecode for the caret line of the source editor, preferring the one the user is looking at.
	 */
	public void syncFromSourceEditor(){
		Editor source = findSourceEditor();
		if(source != null) syncFromSource(source);
	}

	public void refresh(){
		clearHighlights();
		if(enabled.getAsBoolean()) syncFromSourceEditor();
	}

	private void onCaretMoved(Editor editor){
		if(updating || !enabled.getAsBoolean() || mapping.isEmpty()) return;
		if(editor == bytecodeEditor){
			syncFromBytecode();
		}else if(isSourceEditor(editor)){
			// Not worth the work if nobody can see the result
			if(!bytecodeEditor.getComponent().isShowing()) return;
			syncFromSource(editor);
		}
	}

	private void syncFromSource(Editor source){
		clearSourceHighlights();
		clearBytecodeHighlights();

		int line   = source.getCaretModel().getLogicalPosition().line;
		var ranges = mapping.bytecodeRangesFor(line);
		if(ranges.isEmpty()) return;

		Document doc = bytecodeEditor.getDocument();
		for(var range : ranges){
			if(range.endLine()>=doc.getLineCount()) continue;
			bytecodeHighlights.add(highlightLines(bytecodeEditor, range.startLine(), range.endLine()));
		}
		scrollTo(bytecodeEditor, ranges.getFirst().startLine());
	}

	private void syncFromBytecode(){
		clearSourceHighlights();
		clearBytecodeHighlights();

		var file = sourceFile;
		if(file == null || !file.isValid()) return;
		Document sourceDoc = FileDocumentManager.getInstance().getCachedDocument(file);
		if(sourceDoc == null) return;

		int sourceLine = mapping.sourceLineFor(bytecodeEditor.getCaretModel().getLogicalPosition().line);
		if(sourceLine<0 || sourceLine>=sourceDoc.getLineCount()) return;

		for(Editor editor : EditorFactory.getInstance().getEditors(sourceDoc, project)){
			if(editor == bytecodeEditor) continue;
			sourceHighlights.add(highlightLines(editor, sourceLine, sourceLine));
			scrollTo(editor, sourceLine);
		}
	}

	private @Nullable Editor findSourceEditor(){
		if(sourceFile == null) return null;
		Editor selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
		if(selected != null && isSourceEditor(selected)) return selected;
		Document sourceDoc = FileDocumentManager.getInstance().getCachedDocument(sourceFile);
		if(sourceDoc == null) return null;
		Editor[] editors = EditorFactory.getInstance().getEditors(sourceDoc, project);
		return editors.length == 0? null : editors[0];
	}

	private boolean isSourceEditor(Editor editor){
		if(sourceFile == null || editor.getProject() != project) return false;
		return sourceFile.equals(FileDocumentManager.getInstance().getFile(editor.getDocument()));
	}

	private static RangeHighlighter highlightLines(Editor editor, int startLine, int endLine){
		Document doc = editor.getDocument();
		return editor.getMarkupModel().addRangeHighlighter(
			SYNC_LINE,
			doc.getLineStartOffset(startLine),
			doc.getLineEndOffset(endLine),
			HighlighterLayer.SELECTION - 1,
			HighlighterTargetArea.LINES_IN_RANGE
		);
	}

	private static void scrollTo(Editor editor, int line){
		editor.getScrollingModel().scrollTo(new LogicalPosition(line, 0), ScrollType.MAKE_VISIBLE);
	}

	private void clearHighlights(){
		clearBytecodeHighlights();
		clearSourceHighlights();
	}

	private void clearBytecodeHighlights(){
		disposeAll(bytecodeHighlights);
	}

	private void clearSourceHighlights(){
		disposeAll(sourceHighlights);
	}

	private static void disposeAll(List<RangeHighlighter> highlighters){
		for(var h : highlighters){
			if(h.isValid()) h.dispose();
		}
		highlighters.clear();
	}

	@Override
	public void dispose(){
		clearHighlights();
		sourceFile = null;
		mapping = LineMapping.EMPTY;
	}
}
