package com.lc.nlp4han.constituent.unlex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 将分裂后的语法合并
 * 
 * @author 王宁
 * 
 */
public class GrammarMerger
{
	public static void mergeGrammar(Grammar grammar, TreeBank treeBank, double mergeRate)
	{
		double[][] mergeWeight = computerMergerWeight(grammar);
		ArrayList<Short> newNumSubsymbolArr = new ArrayList<>(
				Arrays.asList(new Short[grammar.nonterminalTable.getNumSubsymbolArr().size()]));
		Collections.copy(newNumSubsymbolArr, grammar.nonterminalTable.getNumSubsymbolArr());
		Short[][] mergeSymbols = getMergeSymbol(grammar, treeBank, mergeRate, newNumSubsymbolArr, mergeWeight);
		mergeRule(grammar.bRules, mergeSymbols, mergeWeight);
		mergeRule(grammar.uRules, mergeSymbols, mergeWeight);
		mergeRule(grammar.lexicon.getPreRules(), mergeSymbols, mergeWeight);
		grammar.nonterminalTable.setNumSubsymbolArr(newNumSubsymbolArr);
		grammar.sameParentRulesCount = new HashMap<>();
		mergeWeight = null;
		mergeTrees(grammar, treeBank);
	}

	public static <T extends Rule> void mergeRule(Set<T> rules, Short[][] symbolToMerge, double[][] mergeWeight)
	{
		for (Rule rule : rules)
		{
			rule.merge(symbolToMerge, mergeWeight);
		}
	}

	public static void mergeTrees(Grammar g, TreeBank treeBank)
	{

		for (AnnotationTreeNode tree : treeBank.getTreeBank())
		{
			mergeTreeAnnotation(g, tree);
		}
	}

	public static void mergeTreeAnnotation(Grammar g, AnnotationTreeNode tree)
	{
		if (tree.isLeaf())
			return;
		tree.getLabel().setNumSubSymbol(g.nonterminalTable.getNumSubsymbolArr().get(tree.getLabel().getSymbol()));
		tree.forgetIOScore();
		tree.getLabel().setInnerScores(null);
		tree.getLabel().setOuterScores(null);
		for (AnnotationTreeNode child : tree.getChildren())
		{
			mergeTreeAnnotation(g, child);
		}
	}

	public static double[][] computerMergerWeight(Grammar g)
	{
		double[][] mergerWeight = new double[g.nonterminalTable.getNumSymbol()][];
		// 根节点不分裂、不合并
		for (short i = 1; i < mergerWeight.length; i++)
		{
			mergerWeight[i] = new double[g.nonterminalTable.getNumSubsymbolArr().get(i)];
			for (short j = 0; j < mergerWeight[i].length; j++)
			{
				mergerWeight[i][j] = g.sameParentRulesCount.get(i)[j]
						/ (g.sameParentRulesCount.get(i)[j] + g.sameParentRulesCount.get(i)[j + 1]);
				mergerWeight[i][j + 1] = g.sameParentRulesCount.get(i)[j]
						/ (g.sameParentRulesCount.get(i)[j] + g.sameParentRulesCount.get(i)[j + 1]);
				j++;
			}
		}
		return mergerWeight;
	}

	@SuppressWarnings("unchecked")
	public static Short[][] getMergeSymbol(Grammar g, TreeBank treeBank, double mergeRate,
			ArrayList<Short> newNumSubsymbolArr, double[][] mergeWeight)
	{
		ArrayList<Short>[] symbolToMerge = new ArrayList[g.nonterminalTable.getNumSymbol()];
		PriorityQueue<SentenceLikehood> senScoreGradient = new PriorityQueue<>();
		// assume a subState without split,root 没有分裂
		for (short i = 1; i < g.nonterminalTable.getNumSymbol(); i++)
		{
			for (short j = 0; j < g.nonterminalTable.getNumSubsymbolArr().get(i); j++)
			{
				double tallyGradient = 1;
				System.out.println(
						"若合并非终结符号" + g.nonterminalTable.stringValue(i) + "的第" + j + "," + (j + 1) + "个子符号后树库似然比：");
				for (AnnotationTreeNode tree : treeBank.getTreeBank())
				{
					double sentenceScoreGradient = getMergeSymbolHelper(g, tree, i, j, mergeWeight);
					tallyGradient *= sentenceScoreGradient;
				}
				System.out.println(tallyGradient);
				senScoreGradient.add(new SentenceLikehood(i, j, tallyGradient));
				j++;
			}
		}
		int mergeCount = (int) (senScoreGradient.size() * mergeRate);
		System.out.println("预计合并" + mergeCount + "对子符号。");
		for (int i = 0; i < mergeCount; i++)
		{
			SentenceLikehood s = senScoreGradient.poll();
			if (symbolToMerge[s.symbol] == null)
			{
				symbolToMerge[s.symbol] = new ArrayList<Short>();
			}
			if (s.sentenceScoreGradient < 1)
			{
				System.out.println("实际合并" + i + "对子符号。");
				break;
			}
			symbolToMerge[s.symbol].add(s.subSymbolIndex);

			if (i == mergeCount - 1)
				System.out.println("合并第" + (i + 1) + "对子符号后，树库与合并前的似然值之比为：" + s.sentenceScoreGradient);
		}

		Short[][] mergeSymbols = new Short[symbolToMerge.length][];
		Short[] subSymbolToMerge;
		//// assume a subState without split,root 没有分裂
		for (int i = 1; i < symbolToMerge.length; i++)
		{
			if (symbolToMerge[i] != null)
			{
				symbolToMerge[i].sort(new Comparator<Short>()
				{
					@Override
					public int compare(Short o1, Short o2)
					{
						if (o1 < o2)
							return -1;
						else if (o1 > o2)
							return 1;
						else
							return 0;
					}
				});

				subSymbolToMerge = symbolToMerge[i].toArray(new Short[symbolToMerge[i].size()]);
				mergeSymbols[i] = subSymbolToMerge;
				newNumSubsymbolArr.set(i,
						(short) (g.nonterminalTable.getNumSubsymbolArr().get(i) - subSymbolToMerge.length));
			}
		}
		return mergeSymbols;
	}

	public static double getMergeSymbolHelper(Grammar g, AnnotationTreeNode tree, short symbol, short subSymbolIndex,
			double[][] mergeWeight)
	{
		double senScoreGradient = 1.0;
		for (AnnotationTreeNode child : tree.getChildren())
		{
			if (!child.isLeaf())
				senScoreGradient *= getMergeSymbolHelper(g, child, symbol, subSymbolIndex, mergeWeight);
		}
		if (tree.getLabel().getSymbol() == symbol && symbol != 0)
		{
			senScoreGradient *= calSenSocreAssumeMergeState_i(tree, subSymbolIndex, mergeWeight)
					/ TreeBank.calculateSentenceSocre(tree);
		}
		return senScoreGradient;
	}

	/**
	 * @param 树中要合并的节点
	 * @param 树中要合并的节点的subStateIndex
	 * @return 树的似然值
	 */
	public static double calSenSocreAssumeMergeState_i(AnnotationTreeNode node, int subStateIndex,
			double[][] mergeWeight)
	{

		if (node.getLabel().getInnerScores() == null || node.getLabel().getOuterScores() == null)
			throw new Error("没有计算树上节点的内外向概率。");
		if (node.isLeaf())
			throw new Error("不能利用叶子节点计算内外向概率。");
		if (node.getLabel().getSymbol() == 0)// root不分裂不合并
			return 0;
		double sentenceScore = 0.0;
		Double[] innerScore = node.getLabel().getInnerScores();
		Double[] outerScores = node.getLabel().getOuterScores();
		int numSubState = innerScore.length;
		if (subStateIndex % 2 == 1)
		{
			subStateIndex = subStateIndex - 1;
		}
		for (int i = 0; i < numSubState; i++)
		{
			if (i == subStateIndex)
			{
				double iWeight = mergeWeight[node.getLabel().getSymbol()][i];
				double brotherWeight = mergeWeight[node.getLabel().getSymbol()][i + 1];
				sentenceScore += (iWeight * innerScore[i] + brotherWeight * innerScore[i + 1])
						* (outerScores[i] + outerScores[i + 1]);
				i++;
			}
			else
			{
				sentenceScore += innerScore[i] * outerScores[i];
			}
		}
		return sentenceScore;
	}

	static class SentenceLikehood implements Comparable<SentenceLikehood>
	{
		short symbol;
		short subSymbolIndex;
		double sentenceScoreGradient;

		SentenceLikehood(short symbol, short subSymbolIndex, double sentenceScoreGradient)
		{
			this.sentenceScoreGradient = sentenceScoreGradient;
			this.symbol = symbol;
			this.subSymbolIndex = subSymbolIndex;
		}

		@Override
		public int compareTo(SentenceLikehood o)
		{
			if (this.sentenceScoreGradient > o.sentenceScoreGradient)
				return -1;
			else if (this.sentenceScoreGradient < o.sentenceScoreGradient)
				return 1;
			else
				return 0;
		}
	}
}