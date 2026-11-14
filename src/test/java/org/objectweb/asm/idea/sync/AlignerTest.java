package org.objectweb.asm.idea.sync;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AlignerTest{

	private static int[] align(String a, String b){
		return Aligner.align(a.length(), b.length(), (i, j) -> a.charAt(i) == b.charAt(j)? 1 : 0);
	}

	@Test
	public void identical(){
		assertEquals(align("abc", "abc"), new int[]{0, 1, 2});
	}

	@Test
	public void extraElementsOnBothSides(){
		// a x b c  vs  a b y c
		assertEquals(align("axbc", "abyc"), new int[]{0, -1, 1, 3});
	}

	@Test
	public void empty(){
		assertEquals(align("", "abc"), new int[0]);
		assertEquals(align("ab", ""), new int[]{-1, -1});
	}

	@Test
	public void prefersHigherScore(){
		// a can pair with either b element, but the second one scores higher
		int[] m = Aligner.align(1, 2, (i, j) -> j == 0? 1 : 5);
		assertEquals(m, new int[]{1});
	}

	@Test
	public void keepsOrder(){
		// Crossing pairs are impossible: "ab" vs "ba" can only keep one
		int[] m = align("ab", "ba");
		int   paired = 0;
		for(int i = 0; i<m.length; i++){
			if(m[i] == -1) continue;
			paired++;
			assertEquals("ba".charAt(m[i]), "ab".charAt(i));
		}
		assertEquals(paired, 1);
	}

	@Test
	public void attachUnmatched(){
		// 0 matched, 1 unmatched consumer, 2 unmatched producer, 3 matched, 4 unmatched on another line
		int[] match = {10, -1, -1, 30, -1};
		int[] lines = {1, 1, 1, 1, 2};
		Aligner.attachUnmatched(match, i -> lines[i], i -> i == 2);
		assertEquals(match, new int[]{10, 10, 30, 30, -1});
	}

	@Test
	public void attachFallsBackToOtherSide(){
		int[] match = {-1, 20};
		Aligner.attachUnmatched(match, i -> 0, i -> false);
		assertEquals(match, new int[]{20, 20});
	}
}
