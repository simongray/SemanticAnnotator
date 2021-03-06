package statements.core;

import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.TypedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Utilities for Statement and related classes.
 */
public class StatementUtils {
    private static final Logger logger = LoggerFactory.getLogger(StatementUtils.class);

    /**
     * Joins a list of IndexedWords together in the correct order without putting spaces before commas.
     *
     * @param words the list of words to be joined
     * @return the string representing the words
     */
    public static String join(Set<IndexedWord> words, boolean useLemma, boolean useLowerCase) {
        List<IndexedWord> wordsList = new ArrayList<>(words);
        wordsList.sort(new IndexComparator());

        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < wordsList.size(); i++) {
            IndexedWord indexedWord = wordsList.get(i);
            String tag = indexedWord.tag();
            String word = indexedWord.word();
            String lemma = indexedWord.lemma();

            boolean shortened = isShortened(word);

            if (!tag.equals(".")) {
                if (i != 0 && !tag.equals(",")  && !shortened) {
                    buffer.append(" ");
                }

                if (i == 0 && word.equals("n't")) {
                    buffer.append("not");  // special rule
                } else {
                    String wordString = useLemma && lemma != null? lemma : word;
                    wordString = useLowerCase? wordString.toLowerCase() : wordString;
                    buffer.append(wordString);  // default
                }
            }
        }

        return buffer.toString();
    }

    public static String join(Set<IndexedWord> words) {
        return join(words, false, false);
    }

    /**
     * Flip a map so that keys becomes values and values become keys.
     *
     * @param map
     * @param <T>
     * @return
     */
    public static <T> Map<T, Set<T>> flip(Map<T, T> map) {
        Map<T, Set<T>> flippedMap = new HashMap<>();

        for (T oldKey : map.keySet()) {
            T oldValue = map.get(oldKey);
            Set<T> newValue = flippedMap.getOrDefault(oldValue, new HashSet<>());
            newValue.add(oldKey);
            flippedMap.put(oldValue, newValue);
        }

        return flippedMap;
    }

    /**
     * Remove certain words from a list of words.
     *
     * @param undesired words to remove
     * @param allWords words to remove from
     * @return remaining words
     */
    public static Set<IndexedWord> without(Set<IndexedWord> undesired, Set<IndexedWord> allWords) {
        Set<IndexedWord> remainingWords = new HashSet<>(allWords);
        remainingWords.removeIf(indexedWord -> undesired.contains(indexedWord));
        return remainingWords;
    }

    /**
     * Include certain words with a list of words.
     *
     * @param desired words to remove
     * @param allWords words to remove from
     * @return remaining words
     */
    public static Set<IndexedWord> with(Set<IndexedWord> desired, Set<IndexedWord> allWords) {
        Set<IndexedWord> words = new HashSet<>(allWords);
        words.addAll(desired);
        return words;
    }

    /**
     * Whether or not a word token is shortened.
     *
     * @param word the word to check
     * @return shortened or not
     */
    private static boolean isShortened(String word) {
        return word.startsWith("'") || word.equals("n't");
    }

    /**
     * Recursively finds the words of a compound in a greedy way.
     *
     * @param parent the word that serves as an entry point
     * @param graph the graph of the sentence
     * @param ignoredRelations relation types that shouldn't be followed or included
     * @return compound components
     */
    public static Set<IndexedWord> findCompound(IndexedWord parent, SemanticGraph graph, Set<String> ignoredRelations, Set<String> ownedScopes) {
        Set<IndexedWord> compoundComponents = new HashSet<>();
        compoundComponents.add(parent);

        for (IndexedWord child : graph.getChildren(parent)) {
            GrammaticalRelation relation = graph.reln(parent, child);

            // when encountering an owned scope, then that scope is added in full
            // in other cases, relations are added when they do not appear in the set of ignoredRelations
            if (ignoredRelations == null || !ignoredRelations.contains(relation.getShortName())) {
                if (ownedScopes != null && ownedScopes.contains(relation.getShortName())) {
                    compoundComponents.addAll(findCompound(child, graph));
                } else {
                    compoundComponents.addAll(findCompound(child, graph, null, ignoredRelations));
                }
            }
        }

        return compoundComponents;
    }

    /**
     * Recursively finds the words of a compound in a greedy way.
     *
     * @param parent the word that serves as an entry point
     * @param graph the graph of the sentence
     * @return compound components
     */
    public static Set<IndexedWord> findCompound(IndexedWord parent, SemanticGraph graph) {
        return findCompound(parent, graph, null, null);
    }

    /**
     * Recursively finds the words of a compound in a NON-greedy way.
     * Only relations in ownedScopes will be followed.
     * Useful for defining smaller (= not full) compounds used for later comparison.
     *
     * @param parent the word that serves as an entry point
     * @param graph the graph of the sentence
     * @param ownedScopes relations + descendants of that relation that will be added regardless of ignored relations
     * @return compound components
     */
    public static Set<IndexedWord> findLimitedCompound(IndexedWord parent, SemanticGraph graph, Set<String> ownedScopes) {
        Set<IndexedWord> compoundComponents = new HashSet<>();
        compoundComponents.add(parent);

        for (IndexedWord child : graph.getChildren(parent)) {
            GrammaticalRelation relation = graph.reln(parent, child);

            // when encountering an owned scope, then that scope is added in full
            // in other cases, relations are added when they do not appear in the set of ignoredRelations
            if (ownedScopes != null && ownedScopes.contains(relation.getShortName())) {
                compoundComponents.addAll(findLimitedCompound(child, graph, ownedScopes));
            }
        }

        return compoundComponents;
    }


    /**
     * Recursively finds specific descendants of a word.
     *
     * @param word the word that serves as an entry point
     * @param graph the graph of the sentence
     * @return specific descendants
     */
    public static Set<IndexedWord> findSpecificDescendants(String relation, IndexedWord word, SemanticGraph graph) {
        Set<IndexedWord> specificDescendants = new HashSet<>();

        for (IndexedWord child : graph.getChildren(word)) {
            if (graph.reln(word, child).getShortName().equals(relation)) {
                specificDescendants.addAll(findCompound(child, graph));
            }
        }

        return specificDescendants;
    }

    /**
     * Recursively finds specific descendants of a word.
     *
     * @param word the word that serves as an entry point
     * @param graph the graph of the sentence
     * @return specific descendants
     */
    public static Set<IndexedWord> findSpecificDescendants(ComplexRelation relation, IndexedWord word, SemanticGraph graph) {
        Set<IndexedWord> specificDescendants = new HashSet<>();

        for (IndexedWord child : graph.getChildren(word)) {
            if (relation.evaluate(graph.reln(word, child))) {
                specificDescendants.addAll(findCompound(child, graph));
            }
        }

        return specificDescendants;
    }

    /**
     * Recursively finds specific children of a word.
     * This differs from findSpecificDescendants() in that it only adds direct children.
     * It is useful for finding entries for a component, e.g. all of the subjects in a conjunction.
     *
     * @param word the word that serves as an entry point
     * @param graph the graph of the sentence
     * @return specific children
     */
    public static Set<IndexedWord> findSpecificChildren(String relation, IndexedWord word, SemanticGraph graph) {
        Set<IndexedWord> specificChildren = new HashSet<>();

        for (IndexedWord child : graph.getChildren(word)) {
            if (graph.reln(word, child).getShortName().equals(relation)) {
                specificChildren.add(child);
            }
        }

        return specificChildren;
    }

    /**
     * Recursively finds specific parents of a word.
     * Useful for finding conjunction governors.
     *
     * @param word the word that serves as an entry point
     * @param graph the graph of the sentence
     * @return specific parents
     */
    public static Set<IndexedWord> findSpecificParents(String relation, IndexedWord word, SemanticGraph graph) {
        Set<IndexedWord> specificParents = new HashSet<>();

        for (IndexedWord parent : graph.getParents(word)) {
            if (graph.reln(parent, word).getShortName().equals(relation)) {
                specificParents.add(parent);
            }
        }

        return specificParents;
    }

    /**
     * Create a map of parent-descendants (recursively found) based on a specific type of relation.
     * Useful for isolating words such as negations, markers, copulas, etc.
     *
     * @param words the entries to find relations for
     * @param relation the relation type
     * @param graph the graph to search in
     * @return relations map
     */
    public static Map<IndexedWord, Set<IndexedWord>> makeDescendantMap(Collection<IndexedWord> words, String relation, SemanticGraph graph) {
        Map<IndexedWord, Set<IndexedWord>> relationsMap = new HashMap<>();
        Set<IndexedWord> allChildren = new HashSet<>();

        // map children to parents
        for (IndexedWord word : words) {
            Set<IndexedWord> children = StatementUtils.findSpecificDescendants(relation, word, graph);
            relationsMap.put(word, children);
            allChildren.addAll(children);
        }

        // children cannot also be parents in this simple relations map
        for (IndexedWord child : allChildren) {
            relationsMap.remove(child);
        }

        return relationsMap;
    }

    /**
     * Create a map of direct parent-children relations based on a specific type of relation.
     * Useful for finding conjunction for subjects or objects (but not verbs, see: findJointlyGoverned).
     *
     * @param words the entries to find relations for
     * @param relation the relation type
     * @param graph the graph to search in
     * @return relations map
     */
    public static Map<IndexedWord, Set<IndexedWord>> makeChildMap(Collection<IndexedWord> words, String relation, SemanticGraph graph) {
        Map<IndexedWord, Set<IndexedWord>> relationsMap = new HashMap<>();
        Set<IndexedWord> allChildren = new HashSet<>();

        // map children to parents
        for (IndexedWord word : words) {
            Set<IndexedWord> children = StatementUtils.findSpecificChildren(relation, word, graph);
            relationsMap.put(word, children);
            allChildren.addAll(children);
        }

        // children cannot also be parents in this simple relations map
        for (IndexedWord child : allChildren) {
            relationsMap.remove(child);
        }

        return relationsMap;
    }

    /**
     * Find entries with shared governance of a word in some specific relation.
     * Useful for finding verb conjunction.
     *
     * @param entries
     * @param relation
     * @param graph
     * @return
     */
    public static Map<IndexedWord, Set<IndexedWord>> findSharedGovernance(Set<IndexedWord> entries, String relation, SemanticGraph graph) {
        Map<IndexedWord, Set<IndexedWord>> siblingMapping = new HashMap<>();

        // map parents to their shared child
        for (TypedDependency dependency : graph.typedDependencies()) {
            if (dependency.reln().getShortName().equals(relation)) {
                if (entries.contains(dependency.gov())) {
                    Set<IndexedWord> siblings = siblingMapping.getOrDefault(dependency.dep(), new HashSet<>());
                    siblings.add(dependency.gov());
                    siblingMapping.put(dependency.dep(), siblings);
                }
            }
        }

        return siblingMapping;
    }

    /**
     * Find entries with shared dependence on a word in some specific relation.
     * Useful for finding verb conjunction.
     *
     * @param entries
     * @param relation
     * @param graph
     * @return
     */
    public static Map<IndexedWord, Set<IndexedWord>> findSharedDependence(Set<IndexedWord> entries, String relation, SemanticGraph graph) {
        Map<IndexedWord, Set<IndexedWord>> siblingMapping = new HashMap<>();

        // map siblings to their shared parent
        for (TypedDependency dependency : graph.typedDependencies()) {
            if (dependency.reln().getShortName().equals(relation)) {
                if (entries.contains(dependency.dep())) {
                    Set<IndexedWord> siblings = siblingMapping.getOrDefault(dependency.gov(), new HashSet<>());
                    siblings.add(dependency.dep());
                    siblingMapping.put(dependency.gov(), siblings);
                }
            }
        }

        return siblingMapping;
    }


    /**
     * Create sets of words that are linked into a sequence using the same relation.
     * Useful for finding sequences of indirect objects (ex: "<-nmod- {in a house} <-nmod- {in Copenhagen}")
     *
     * @param entries
     * @param relation
     * @param graph
     * @return
     */
    public static Set<Set<IndexedWord>> findSequences(Set<IndexedWord> entries, String relation, SemanticGraph graph) {
        Set<Set<IndexedWord>> sequences = new HashSet<>();

        for (IndexedWord entry : entries) {
            for (IndexedWord otherEntry : entries) {
                if (entry != otherEntry) {
                    GrammaticalRelation grammaticalRelation = graph.reln(entry, otherEntry);

                    // add any two entries connected by the stated relation to the set of sequences
                    if (grammaticalRelation != null && grammaticalRelation.getShortName().equals(relation)) {
                        Set<IndexedWord> sequence = new HashSet<>();
                        sequence.add(entry);
                        sequence.add(otherEntry);
                        sequences.add(sequence);
                    }
                }
            }
        }

        merge(sequences);

        return sequences;
    }

    /**
     * Transform a set of IndexedWords (e.g. from a component) to an ordered list of lemmatised words (if available).
     *
     * @param words input words set
     * @return lemmatised words list
     */
    public static List<String> lemmatise(Set<IndexedWord> words) {
        List<IndexedWord> orderedWords = new ArrayList<>(words);
        orderedWords.sort(new IndexComparator());
        List<String> lemmatisedWords = new ArrayList<>();

        for (IndexedWord word : orderedWords) {
            if (word.lemma() != null) {
                lemmatisedWords.add(word.lemma());
            } else {
                lemmatisedWords.add(word.word());
            }
        }

        return lemmatisedWords;
    }

    /**
     * Get relevant text search keywords for finding resemblance to statements in text.
     * The keywords are useful for finding pieces of text that *may* contain resembling statements.
     * These pieces of text will then have to be put through the StatementAnnotator,
     * in order to get a set of statements for comparing to the original statements.
     *
     * @param statements statements to match
     * @return search keywords
     */
    public static Set<String> getSearchKeywords(Set<Statement> statements) {
        return null;  // TODO: implement, apply some filter like stopwords to total words and return words that survive
    }

    /**
     * Print statements of a sentence in a pretty way.
     *
     * @param statements statements from a sentence.
     */
    public static void printStatements(Set<Statement> statements) {
        printStatements(statements, "");
    }

    /**
     * Helper method for printStatements(CoreMap sentence).
     *
     * @param statements statements from a sentence.
     */
    private static void printStatements(Set<Statement> statements, String indent) {
        if (statements != null) {
            int i = 0;
            for (Statement statement : statements) {
                System.out.println(indent + "  |_ statement: " + statement);

                for (StatementComponent component : statement.getComponents()) {
                    String prefix = i < statements.size() - 1? "  |  |_ " : "     |_ ";
                    if (component instanceof Statement) {
                        System.out.println(indent + prefix + "component: " + component + "  ->  " + ((Statement) component).getComponents());
                    } else {
                        System.out.println(indent + prefix + "component: " + component);
                    }
                }

                i++;
            }
        } else {
            System.out.println("  |_ NO STATEMENTS");
        }
    }

    /**
     * Test whether two sets intersect.
     *
     * @param set1 set 1
     * @param set2 set 2
     * @return
     */
    public static <T> boolean intersects(Set<T> set1, Set<T> set2) {
        for (T component : set1) {
            for (T otherComponent : set2) {
                if (component.equals(otherComponent)) return true;
            }
        }

        return false;
    }

    /**
     * Merge sets that intersect.
     * Useful for merging component or verb sets, for example.
     *
     * @param objectSets
     * @return
     */
    public static <T> Set<Set<T>> merge(Collection<Set<T>> objectSets) {
        Set<Set<T>> mergedObjectSets = new HashSet<>();
        Set<Set<T>> unmergedObjectSets = new HashSet<>(objectSets);

        for (Set<T> objectSet : objectSets) {
            if (unmergedObjectSets.isEmpty()) break;

            for (Set<T> otherObjectSet : objectSets) {
                if (unmergedObjectSets.isEmpty()) break;

                // merge with the first intersecting set
                if (!objectSet.equals(otherObjectSet) && intersects(objectSet, otherObjectSet)) {
                    Set<T> mergedObjectSet = new HashSet<>(objectSet);
                    mergedObjectSet.addAll(otherObjectSet);
                    mergedObjectSets.add(mergedObjectSet);
                    unmergedObjectSets.remove(objectSet);
                    unmergedObjectSets.remove(otherObjectSet);
                }
            }
        }

        // add remaining unmerged sets
        mergedObjectSets.addAll(unmergedObjectSets);

        // merges until there is no change
        if (!mergedObjectSets.equals(objectSets)) {
            mergedObjectSets = merge(mergedObjectSets);
        }

        return mergedObjectSets;
    }

    /**
     * Used to sort IndexedWords by index.
     */
    public static class IndexComparator implements Comparator<IndexedWord> {
        @Override
        public int compare(IndexedWord x, IndexedWord y) {
            int xn = x.index();
            int yn = y.index();
            if (xn == yn) {
                return 0;
            } else {
                if (xn > yn) {
                    return 1;
                } else {
                    return -1;
                }
            }
        }
    }
}
