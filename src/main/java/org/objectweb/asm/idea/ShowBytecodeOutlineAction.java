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

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Caret;
import org.jetbrains.annotations.NotNull;


/**
 * Shows the bytecode of the class at the caret (or of the selected file) in the ASM tool window.
 *
 * @see OutlineLoader
 */
public class ShowBytecodeOutlineAction extends AnAction{
	
	@Override
	public void update(final @NotNull AnActionEvent e){
		final var virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
		final var project     = e.getProject();
		e.getPresentation().setEnabled(project != null && virtualFile != null && OutlineLoader.canLoad(project, virtualFile));
	}
	
	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread(){
		return ActionUpdateThread.BGT;
	}
	
	@Override
	public void actionPerformed(final @NotNull AnActionEvent e){
		final var virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
		final var project     = e.getProject();
		if(project == null || virtualFile == null) return;
		final Caret caret = e.getData(CommonDataKeys.CARET);
		OutlineLoader.getInstance(project).load(virtualFile, caret == null? -1 : caret.getOffset(), true);
	}
}
