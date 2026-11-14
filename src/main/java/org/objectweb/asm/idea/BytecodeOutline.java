/*
 *
 *  Copyright 2011 Cédric Champeau
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.objectweb.asm.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.OnePixelSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.idea.config.ASMPluginComponent;
import org.objectweb.asm.idea.docs.InsnDocPanel;
import org.objectweb.asm.idea.sync.CaretSync;
import org.objectweb.asm.idea.sync.FollowEditor;
import org.objectweb.asm.idea.sync.InsnMapping;
import org.objectweb.asm.idea.sync.RecordingTextifier;


/**
 * Created by IntelliJ IDEA.
 * User: cedric
 * Date: 07/01/11
 * Time: 17:07
 * Bytecode view.
 */
public class BytecodeOutline extends ACodeView{
	
	private final CaretSync        caretSync;
	private final FollowEditor     followEditor;
	private final InsnDocPanel     docPanel;
	private final org.objectweb.asm.idea.nav.BytecodeEditorSupport editorSupport;
	private final OnePixelSplitter splitter;
	/** The instruction printed on each line of the bytecode text, null entries for other lines */
	private RecordingTextifier.Printed[] insnsByLine = new RecordingTextifier.Printed[0];
	
	public BytecodeOutline(final Project project){
		super(project, org.objectweb.asm.idea.highlight.BytecodeFileType.INSTANCE);
		caretSync = new CaretSync(project, editor, () -> config().isFollowEditor(), () -> config().isJavaSyntaxMapping());
		followEditor = new FollowEditor(project, editor, () -> config().isFollowEditor());
		Disposer.register(this, caretSync);
		Disposer.register(this, followEditor);
		toolbarActions.add(new FollowEditorAction());
		toolbarActions.add(new JavaSyntaxMappingAction());
		toolbarActions.add(new InstructionDocsAction());

		editorSupport = new org.objectweb.asm.idea.nav.BytecodeEditorSupport(project, editor, caretSync::lambdaSource);
		Disposer.register(this, editorSupport);
		editor.getSettings().setFoldingOutlineShown(true);

		docPanel = new InsnDocPanel(project);
		Disposer.register(this, docPanel);
		splitter = new OnePixelSplitter(true, "asm.bytecode.instructionDocs", 0.65f);
		splitter.setFirstComponent(editor.getComponent());
		setContent(splitter);
		updateDocPanelVisibility();
		editor.getCaretModel().addCaretListener(new CaretListener(){
			@Override
			public void caretPositionChanged(@NotNull CaretEvent event){
				showInsnDocs();
			}
		}, this);
		// The tool window may have been opened before this view existed to hear about it
		DumbService.getInstance(project).smartInvokeLater(followEditor::followSelectedEditor);
	}
	
	public static BytecodeOutline getInstance(Project project){
		return project.getService(BytecodeOutline.class);
	}
	
	private ASMPluginComponent config(){
		return project.getService(ASMPluginComponent.class);
	}
	
	/**
	 * @param classFile  the class file the bytecode was read from
	 * @param sourceFile  the file the class was compiled from, used for caret syncing. Null if unknown.
	 * @param insnMapping maps instructions to source expressions, null to only match by line
	 */
	public void setCode(final VirtualFile classFile, final @Nullable VirtualFile sourceFile, final String code,
	                    final @Nullable RecordingTextifier.Result printed, final @Nullable InsnMapping insnMapping){
		setCode(classFile, sourceFile, code, printed, insnMapping, java.util.function.IntUnaryOperator.identity());
	}
	
	/**
	 * @param lineMap the class file's line numbers to lines of the source file, which differ for decompiled code
	 */
	public void setCode(final VirtualFile classFile, final @Nullable VirtualFile sourceFile, final String code,
	                    final @Nullable RecordingTextifier.Result printed, final @Nullable InsnMapping insnMapping,
	                    final java.util.function.IntUnaryOperator lineMap){
		insnsByLine = indexByLine(printed);
		caretSync.update(sourceFile, () -> super.setCode(classFile, code), code, insnMapping, lineMap);
		editorSupport.updateFolding(printed);
		showInsnDocs();
	}
	
	@Override
	public void setCode(final VirtualFile file, final String code){
		setCode(file, null, code, null, null);
	}
	
	/** What is printed on each line, null entries for lines with nothing to explain */
	static RecordingTextifier.Printed[] indexByLine(@Nullable RecordingTextifier.Result printed){
		if(printed == null) return new RecordingTextifier.Printed[0];
		int lines = (int)printed.text().chars().filter(c -> c == '\n').count() + 1;
		var byLine = new RecordingTextifier.Printed[lines];
		for(var m : printed.methods()){
			for(var insn : m.insns) mark(byLine, insn);
			for(var frame : m.frames) mark(byLine, frame);
			for(var info : m.infos) mark(byLine, info);
		}
		return byLine;
	}
	
	private static void mark(RecordingTextifier.Printed[] byLine, RecordingTextifier.Printed p){
		for(int l = Math.max(0, p.textStart()); l<=p.textEnd() && l<byLine.length; l++) byLine[l] = p;
	}
	
	/**
	 * Shows the tool window and lists the usages of a symbol in the bytecode shown.
	 *
	 * @param from the editor the search started from, for messages
	 */
	public void showUsages(org.objectweb.asm.idea.nav.BytecodeUsages.Symbol symbol, java.util.function.BiPredicate<String, String> sameOwner,
	                       com.intellij.openapi.editor.Editor from){
		if(document.getTextLength() == 0){
			com.intellij.codeInsight.hint.HintManager.getInstance().showInformationHint(from, "No bytecode is shown. Show the bytecode of this class first.");
			return;
		}
		var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(OutlineLoader.TOOL_WINDOW_ID);
		Runnable show = () -> editorSupport.showUsages(symbol, sameOwner, from);
		if(toolWindow == null) show.run();
		else toolWindow.activate(show);
	}
	
	private void showInsnDocs(){
		if(!config().isInstructionDocs() || editor == null) return;
		int line = editor.getCaretModel().getLogicalPosition().line;
		docPanel.show(line<insnsByLine.length? insnsByLine[line] : null);
	}
	
	private void updateDocPanelVisibility(){
		boolean visible = config().isInstructionDocs();
		splitter.setSecondComponent(visible? docPanel : null);
		if(visible) showInsnDocs();
		else docPanel.show(null);
	}
	
	/**
	 * Highlights and scrolls to the bytecode of the line the caret is on in the source editor.
	 */
	public void syncWithSourceCaret(){
		if(config().isFollowEditor()) caretSync.syncFromSourceEditor();
	}
	
	private final class FollowEditorAction extends ToggleAction{
		
		private FollowEditorAction(){
			super("Follow Editor",
			      "Show the bytecode of the file being edited, highlight the bytecode of the source line under the caret, and the source line of the selected bytecode",
			      AllIcons.General.AutoscrollFromSource
			);
		}
		
		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread(){
			return ActionUpdateThread.EDT;
		}
		
		@Override
		public boolean isSelected(@NotNull AnActionEvent e){
			return config().isFollowEditor();
		}
		
		@Override
		public void setSelected(@NotNull AnActionEvent e, boolean state){
			config().setFollowEditor(state);
			caretSync.refresh();
			followEditor.followSelectedEditor();
		}
	}
	
	private final class JavaSyntaxMappingAction extends ToggleAction{
		
		private JavaSyntaxMappingAction(){
			super("Java Syntax Interpretation",
			      "Match individual instructions to the Java expressions they come from, instead of whole lines",
			      AllIcons.FileTypes.Java
			);
		}
		
		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread(){
			return ActionUpdateThread.EDT;
		}
		
		@Override
		public void update(@NotNull AnActionEvent e){
			super.update(e);
			// Only syncing uses it
			e.getPresentation().setEnabled(config().isFollowEditor());
		}
		
		@Override
		public boolean isSelected(@NotNull AnActionEvent e){
			return config().isJavaSyntaxMapping();
		}
		
		@Override
		public void setSelected(@NotNull AnActionEvent e, boolean state){
			config().setJavaSyntaxMapping(state);
			caretSync.refresh();
		}
	}
	
	private final class InstructionDocsAction extends ToggleAction{
		
		private InstructionDocsAction(){
			super("Instruction Documentation",
			      "Show what the instruction under the caret does, with its description from the Java Virtual Machine Specification",
			      AllIcons.Toolwindows.Documentation
			);
		}
		
		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread(){
			return ActionUpdateThread.EDT;
		}
		
		@Override
		public boolean isSelected(@NotNull AnActionEvent e){
			return config().isInstructionDocs();
		}
		
		@Override
		public void setSelected(@NotNull AnActionEvent e, boolean state){
			config().setInstructionDocs(state);
			updateDocPanelVisibility();
		}
	}
}
