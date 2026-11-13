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
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.idea.config.ASMPluginComponent;
import org.objectweb.asm.idea.sync.CaretSync;
import org.objectweb.asm.idea.sync.FollowEditor;


/**
 * Created by IntelliJ IDEA.
 * User: cedric
 * Date: 07/01/11
 * Time: 17:07
 * Bytecode view.
 */
public class BytecodeOutline extends ACodeView{
	
	private final CaretSync    caretSync;
	private final FollowEditor followEditor;
	
	public BytecodeOutline(final Project project){
		super(project);
		caretSync = new CaretSync(project, editor, () -> config().isFollowEditor());
		followEditor = new FollowEditor(project, editor, () -> config().isFollowEditor());
		Disposer.register(this, caretSync);
		Disposer.register(this, followEditor);
		toolbarActions.add(new FollowEditorAction());
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
	 * @param sourceFile the file the class was compiled from, used for caret syncing. Null if unknown.
	 */
	public void setCode(final VirtualFile classFile, final @Nullable VirtualFile sourceFile, final String code){
		caretSync.update(sourceFile, () -> super.setCode(classFile, code), code);
	}
	
	@Override
	public void setCode(final VirtualFile file, final String code){
		setCode(file, null, code);
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
}
