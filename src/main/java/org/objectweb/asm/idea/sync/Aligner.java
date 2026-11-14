package org.objectweb.asm.idea.sync;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Order preserving matching of two sequences, used to pair bytecode instructions with the source expressions that
 * produced them. Both sequences are in evaluation order, so the best matching is the highest scoring common
 * subsequence (a weighted LCS).
 */
public final class Aligner{
	
	/**
	 * Above this many cells the quadratic table is not worth it, callers should split the input.
	 */
	public static final long MAX_CELLS = 4_000_000;
	
	private Aligner(){ }
	
	/**
	 * @param score score of pairing a with b, &lt;= 0 if they cannot be paired
	 * @return for each element of a, the index of the paired element of b or -1
	 */
	public static int[] align(int aLen, int bLen, IntBinaryOperator score){
		int[] result = new int[aLen];
		Arrays.fill(result, -1);
		if(aLen == 0 || bLen == 0) return result;
		
		int   w     = bLen + 1;
		int[] table = new int[(aLen + 1)*w];
		for(int i = 1; i<=aLen; i++){
			for(int j = 1; j<=bLen; j++){
				int best = Math.max(table[(i - 1)*w + j], table[i*w + j - 1]);
				int s    = score.applyAsInt(i - 1, j - 1);
				if(s>0) best = Math.max(best, table[(i - 1)*w + j - 1] + s);
				table[i*w + j] = best;
			}
		}
		
		int i = aLen, j = bLen;
		while(i>0 && j>0){
			int cur = table[i*w + j];
			if(cur == table[(i - 1)*w + j]){
				i--;
			}else if(cur == table[i*w + j - 1]){
				j--;
			}else{
				result[i - 1] = j - 1;
				i--;
				j--;
			}
		}
		return result;
	}
	
	/**
	 * Gives unpaired elements the pairing of a neighbour: instructions such as DUP, POP, CHECKCAST or boxing calls
	 * have no source expression of their own but clearly belong to the one next to them.
	 *
	 * @param match           result of {@link #align}, modified in place
	 * @param line            source line of each element, -1 if unknown. Only neighbours on the same line are used.
	 * @param attachesForward true if the element produces a value for what follows (loads, constants), false if it
	 *                        consumes the value of what precedes it
	 */
	public static void attachUnmatched(int[] match, IntUnaryOperator line, IntPredicate attachesForward){
		int   n    = match.length;
		int[] prev = new int[n], next = new int[n];
		int   last = -1;
		for(int i = 0; i<n; i++){
			prev[i] = last;
			if(match[i] != -1) last = i;
		}
		last = -1;
		for(int i = n - 1; i>=0; i--){
			next[i] = last;
			if(match[i] != -1) last = i;
		}
		
		int[] original = match.clone();
		for(int i = 0; i<n; i++){
			if(original[i] != -1) continue;
			int p = prev[i] != -1 && line.applyAsInt(prev[i]) == line.applyAsInt(i)? prev[i] : -1;
			int q = next[i] != -1 && line.applyAsInt(next[i]) == line.applyAsInt(i)? next[i] : -1;
			int pick;
			if(attachesForward.test(i)) pick = q != -1? q : p;
			else pick = p != -1? p : q;
			if(pick != -1) match[i] = original[pick];
		}
	}
}
