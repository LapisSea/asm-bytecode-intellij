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

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.PopupHandler;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.idea.config.ASMPluginConfigurable;

import javax.swing.*;
import java.awt.BorderLayout;

/**
 * Created by IntelliJ IDEA.
 * User: cedric
 * Date: 07/01/11
 * Time: 22:18
 * Base class for editors which displays bytecode or ASMified code.
 *
 * @author Cédric Champeau
 * @author Thiakil (December 2017)
 */
public class ACodeView extends SimpleToolWindowPanel implements Disposable{
	protected final Project  project;
	private final   FileType fileType;
	
	
	protected Editor             editor;
	protected Document           document;
	protected DefaultActionGroup toolbarActions;
	// used for diff view
	private   String             previousCode;
	private   VirtualFile        previousFile;
	
	public ACodeView(final Project project, final String fileExtension){
		this(project, FileTypeManager.getInstance().getFileTypeByExtension(fileExtension));
	}
	
	public ACodeView(final Project project, final FileType fileType){
		super(true, true);
		this.project = project;
		this.fileType = fileType;
		setupUI();
	}
	
	public ACodeView(final Project project){
		this(project, "java");
	}
	
	private void setupUI(){
		final EditorFactory editorFactory = EditorFactory.getInstance();
		document = editorFactory.createDocument("");
		editor = editorFactory.createEditor(document, project, fileType, true);
		
		final JComponent editorComponent = editor.getComponent();
		add(editorComponent);
		final AnAction     diffAction = createShowDiffAction();
		DefaultActionGroup group      = new DefaultActionGroup();
		toolbarActions = group;
		group.add(diffAction);
		group.add(new ShowSettingsAction());
		
		final ActionManager actionManager = ActionManager.getInstance();
		final ActionToolbar actionToolBar = actionManager.createActionToolbar("ASM", group, true);
		final JPanel        buttonsPanel  = new JPanel(new BorderLayout());
		buttonsPanel.add(actionToolBar.getComponent(), BorderLayout.CENTER);
		PopupHandler.installPopupMenu(editor.getContentComponent(), group, "ASM");
		setToolbar(buttonsPanel);
	}
	
	public void setCode(final VirtualFile file, final String code){
		final String text = document.getText();
		if(previousFile == null || file == null || previousFile.getPath().equals(file.getPath()) && !text.isEmpty()){
			if(file != null) previousCode = text;
		}else if(!previousFile.getPath().equals(file.getPath())){
			previousCode = ""; // reset previous code
		}
		document.setText(code);
		if(file != null) previousFile = file;
		editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(0), ScrollType.MAKE_VISIBLE);
	}
	
	
	public void dispose(){
		if(editor != null){
			final EditorFactory editorFactory = EditorFactory.getInstance();
			editorFactory.releaseEditor(editor);
			editor = null;
		}
	}
	
	private AnAction createShowDiffAction(){
		return new ShowDiffAction();
	}
	
	private final class ShowSettingsAction extends AnAction{
		
		private ShowSettingsAction(){
			super("Settings", "Show settings for ASM plugin",
			      AllIcons.General.Settings
			);
		}
		
		@Override
		public void actionPerformed(final @NotNull AnActionEvent e){
			ShowSettingsUtil.getInstance().showSettingsDialog(project, ASMPluginConfigurable.class);
		}
	}
	
	private class ShowDiffAction extends AnAction{
		
		public ShowDiffAction(){
			super("Show Differences",
			      "Shows differences from the previous version of bytecode for this file",
			      AllIcons.Actions.Diff
			);
		}
		
		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread(){
			return ActionUpdateThread.EDT;
		}
		
		@Override
		public void update(final AnActionEvent e){
			e.getPresentation().setEnabled(!"".equals(previousCode) && (previousFile != null));
		}
		
		@Override
		public void actionPerformed(final @NotNull AnActionEvent e){
			var psiFile        = fileType;
			var currentContent = DiffContentFactory.getInstance().create(previousFile == null? "" : document.getText(), psiFile);
			var oldContent     = DiffContentFactory.getInstance().create(previousCode == null? "" : previousCode, psiFile);
			DiffManager.getInstance().showDiff(
				project,
				new SimpleDiffRequest(
					"Show Differences from Previous Class Contents",
					oldContent, currentContent,
					"Previous version", "Current version"
				));
		}
	}
}
