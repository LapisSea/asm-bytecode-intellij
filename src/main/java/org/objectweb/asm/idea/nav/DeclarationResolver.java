package org.objectweb.asm.idea.nav;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.ClassUtil;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.idea.highlight.BytecodeReference;

/**
 * Finds the declaration a reference in the bytecode text points to.
 */
public final class DeclarationResolver{
	private DeclarationResolver(){ }

	/**
	 * Must be called in a read action, in smart mode.
	 *
	 * @return the declaration, the class if the member is not found, or null if the class is not found
	 */
	public static @Nullable PsiElement resolve(Project project, BytecodeReference ref){
		if(ref.owner() == null) return null;
		PsiClass cls = ClassUtil.findPsiClassByJVMName(PsiManager.getInstance(project), ref.owner().replace('/', '.'));
		if(cls == null || ref.kind() == BytecodeReference.Kind.CLASS) return cls;
		if(ref.kind() == BytecodeReference.Kind.FIELD){
			var field = cls.findFieldByName(ref.name(), true);
			return field != null? field : cls;
		}
		if(ref.name().equals("<clinit>")) return cls;
		boolean     ctor       = ref.name().equals("<init>");
		PsiMethod[] candidates = ctor? cls.getConstructors() : cls.findMethodsByName(ref.name(), true);
		if(ref.descriptor() != null){
			for(var m : candidates){
				if(ref.descriptor().equals(signature(m))) return m;
			}
		}
		// No exact match, e.g. constructors with parameters javac adds: the first one with the name is close enough
		return candidates.length>0? candidates[0] : cls;
	}

	private static @Nullable String signature(PsiMethod m){
		try{
			return ClassUtil.getAsmMethodSignature(m);
		}catch(RuntimeException e){
			return null;
		}
	}
}
