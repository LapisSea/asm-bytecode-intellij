package org.objectweb.asm.idea.nav;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.ClassUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.idea.BytecodeOutline;
import org.objectweb.asm.idea.OutlineLoader;
import org.objectweb.asm.idea.highlight.BytecodeReference;

import java.util.HashMap;
import java.util.function.BiPredicate;

/**
 * From a method, field or class in Java source: where the bytecode shown uses it.
 */
public final class FindUsagesInBytecodeAction extends AnAction{
	
	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread(){
		return ActionUpdateThread.BGT;
	}
	
	@Override
	public void update(@NotNull AnActionEvent e){
		var     editor    = e.getData(CommonDataKeys.EDITOR);
		var     file      = e.getData(CommonDataKeys.PSI_FILE);
		boolean available = editor != null && file instanceof PsiJavaFile && !DumbService.isDumb(file.getProject());
		e.getPresentation().setEnabledAndVisible(available);
		if(available) e.getPresentation().setEnabled(symbol(target(editor)) != null);
	}
	
	@Override
	public void actionPerformed(@NotNull AnActionEvent e){
		var editor  = e.getData(CommonDataKeys.EDITOR);
		var project = e.getProject();
		if(editor == null || project == null) return;
		var target = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>)() -> target(editor));
		var symbol = ApplicationManager.getApplication().runReadAction((Computable<BytecodeUsages.Symbol>)() -> symbol(target));
		if(symbol == null) return;
		BytecodeOutline.getInstance(project).showUsages(symbol, sameOwner(project), editor);
	}
	
	private static @Nullable PsiElement target(Editor editor){
		var util = TargetElementUtil.getInstance();
		return TargetElementUtil.findTargetElement(editor, util.getAllAccepted());
	}
	
	static @Nullable BytecodeUsages.Symbol symbol(@Nullable PsiElement e){
		return switch(e){
			case PsiMethod m -> {
				String owner = internalName(m.getContainingClass());
				if(owner == null) yield null;
				String descriptor;
				try{
					descriptor = ClassUtil.getAsmMethodSignature(m);
				}catch(RuntimeException ex){
					descriptor = null;
				}
				yield new BytecodeUsages.MemberSymbol(BytecodeReference.Kind.METHOD, owner, m.isConstructor()? "<init>" : m.getName(), descriptor);
			}
			case PsiField f -> {
				String owner = internalName(f.getContainingClass());
				yield owner == null? null : new BytecodeUsages.MemberSymbol(BytecodeReference.Kind.FIELD, owner, f.getName(), null);
			}
			case PsiClass c when !(c instanceof PsiTypeParameter) -> {
				String name = internalName(c);
				yield name == null? null : new BytecodeUsages.ClassSymbol(name);
			}
			case null, default -> null;
		};
	}
	
	private static @Nullable String internalName(@Nullable PsiClass c){
		String jvm = c == null? null : OutlineLoader.getJVMClassName(c);
		return jvm == null? null : jvm.replace('.', '/');
	}
	
	/**
	 * A call through a subclass is compiled with the subclass as the owner, but it is still a use of the member.
	 */
	private static BiPredicate<String, String> sameOwner(Project project){
		var cache = new HashMap<String, Boolean>();
		return (used, declared) -> cache.computeIfAbsent(used + " " + declared, k -> ApplicationManager.getApplication().runReadAction((Computable<Boolean>)() -> {
			if(DumbService.isDumb(project)) return false;
			var manager = PsiManager.getInstance(project);
			var sub     = ClassUtil.findPsiClassByJVMName(manager, used.replace('/', '.'));
			var sup     = ClassUtil.findPsiClassByJVMName(manager, declared.replace('/', '.'));
			return sub != null && sup != null && sub.isInheritor(sup, true);
		}));
	}
}
