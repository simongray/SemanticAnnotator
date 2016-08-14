package statements.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import statements.core.*;
import statements.patterns.*;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public class Profile {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    Set<Statement> statements;
    Set<Statement> interestingStatements = new HashSet<>();
    Set<String> subjectWords = new HashSet<>();
    Set<String> verbWords = new HashSet<>();
    Set<String> directObjectWords = new HashSet<>();
    Set<String> indirectObjectWords = new HashSet<>();
    Set<String> topics = new HashSet<>();
    Set<String> locations = new HashSet<>();
    Set<String> possessions = new HashSet<>();
    Set<String> studies = new HashSet<>();
    Set<String> work = new HashSet<>();
    Set<String> properNouns = new HashSet<>();
    Map<Statement, Double> qualityMap = new HashMap<>();
    private static DecimalFormat df = new DecimalFormat("#.##");

    /**
     * This set of nouns comprises second and third person pronouns, but not first person.
     * It also adds other words that do not carry any information without context.
     */
    private static final Set<String> UNINTERESTING_NOUNS = new HashSet<>();
    static {
        UNINTERESTING_NOUNS.add("yours");
        UNINTERESTING_NOUNS.add("he");
        UNINTERESTING_NOUNS.add("she");
        UNINTERESTING_NOUNS.add("it");
        UNINTERESTING_NOUNS.add("they");
        UNINTERESTING_NOUNS.add("him");
        UNINTERESTING_NOUNS.add("his");
        UNINTERESTING_NOUNS.add("her");
        UNINTERESTING_NOUNS.add("them");
        UNINTERESTING_NOUNS.add("this");
        UNINTERESTING_NOUNS.add("that");
        UNINTERESTING_NOUNS.add("these");
        UNINTERESTING_NOUNS.add("those");
        UNINTERESTING_NOUNS.add("their");
        UNINTERESTING_NOUNS.add("theirs");
        UNINTERESTING_NOUNS.add("here");
        UNINTERESTING_NOUNS.add("there");
        UNINTERESTING_NOUNS.add("who");
        UNINTERESTING_NOUNS.add("what");
        UNINTERESTING_NOUNS.add("which");
        UNINTERESTING_NOUNS.add("all");
        UNINTERESTING_NOUNS.add("thing");
        UNINTERESTING_NOUNS.add("one");
        UNINTERESTING_NOUNS.add("some");
        UNINTERESTING_NOUNS.add("someone");
        UNINTERESTING_NOUNS.add("here");
        UNINTERESTING_NOUNS.add("there");
    }

    /**
     * Matches statements that are deemed interesting (or uninteresting for the anti-patterns).
     * Used to limit statements for further processing based on a couple of heuristics.
     */
    private final StatementPattern EMBEDDED_INTERESTING_PATTERN = new StatementPattern(
            new VerbPattern(),
            new NonVerbPattern().person(Person.first, Person.third).local(false).notWords(UNINTERESTING_NOUNS).all()
    );

    private final StatementPattern INTERESTING_PATTERN = new StatementPattern(
            new SubjectPattern(),
            new VerbPattern(),
            new NonVerbPattern().person(Person.first, Person.third).local(false).notWords(UNINTERESTING_NOUNS).all(),
            EMBEDDED_INTERESTING_PATTERN.optional()  // for embedded statements
    ).question(false);

    private final StatementPattern INTERESTING_ANTIPATTERN_1 = new StatementPattern(
            new VerbPattern().copula()
    ).size(2);

    /**
     * Matches statements that came from a question.
     */
    private final StatementPattern CITATION_PATTERN = new StatementPattern().citation();

    /**
     * Matches anything that is personal in nature, i.e. referring to first person or first person possessions.
     */
    private final MultiPattern PERSONAL_PATTERN = new MultiPattern(
            new NonVerbPattern().firstPerson(),
            new NonVerbPattern().noun().firstPersonPossessive()
    );

    /**
     * Captures proper nouns.
     */
    private final StatementPattern PROPER_NOUN_PATTERN = new StatementPattern(
            new NonVerbPattern().properNoun().notWords(UNINTERESTING_NOUNS).capture()
    );

    /**
     * Captures objects that indicate the occupation of the author
     */
    private final StatementPattern STUDY_PATTERN = new StatementPattern(
            new SubjectPattern().firstPerson(),
            new VerbPattern().words("study"),
            new ObjectPattern().capture().optional().notWords(UNINTERESTING_NOUNS)
    );

    private final StatementPattern WORK_PATTERN = new StatementPattern(
            new SubjectPattern().firstPerson(),
            new VerbPattern().words("work"),
            new IndirectObjectPattern().capture().optional().notWords(UNINTERESTING_NOUNS)
    );

    /**
     * Captures objects that indicate the location of the author.
     */
    private final StatementPattern LOCATION_PATTERN = new StatementPattern(
            new SubjectPattern().firstPerson(),
            new VerbPattern().words(Common.LOCATION_VERB),
            new ObjectPattern().preposition(Common.LOCATION_PREPOSITION).partsOfSpeech(Tag.noun, Tag.properNoun).capture()
    );

    /**
     * Captures objects that indicate the possessions of the author.
     */
    private final StatementPattern POSSESSION_PATTERN_1 = new StatementPattern(
            new NonVerbPattern().noun().firstPersonPossessive().capture()
    );

    private final StatementPattern POSSESSION_PATTERN_2 = new StatementPattern(
            new SubjectPattern().firstPerson(),
            new VerbPattern().words("have", "own", "possess"),
            new DirectObjectPattern().noun().capture().notWords(UNINTERESTING_NOUNS)
    );

    /**
     * Matches statements that indicate the opinion of the author.
     */
    private final StatementPattern OPINION_PATTERN_1 = new StatementPattern(
            new SubjectPattern().firstPerson(),
            new VerbPattern().words(Common.OPINION_VERB),
            new StatementPattern().capture()
    );

    // Note: use the non-captures!
    private final StatementPattern OPINION_PATTERN_2 = new StatementPattern(
            new ObjectPattern().firstPersonPossessive().words(Common.OPINION_NOUN).preposition().capture()
    );

    public Profile(Set<Statement> statements) throws IOException {
        this.statements = statements;

        // citations do not represent the user's own opinions
        int statementCount = statements.size();
        statements.removeIf(CITATION_PATTERN::matches);
        logger.info("removed citations: " + (statementCount - statements.size()));

        // unpack embedded statements according to a pattern
        // the original statements are replaced with the embedded statements based on the pattern
        unpackEmbeddedStatements();

        // find locations that the author has been to
        registerLocations();

        // find possessions of the author
        registerPossessions();

        // find occupations of the author
        registerOccupations();

        // find pronouns mentioned by the author
        registerProperNouns();

        // store the topic keywords of all interesting statements
        // used to rank statements in relation to other users
        registerTopics();
    }

    /**
     * Unpack statements according to certain patterns to replace them with their embedded statements.
     */
    private void unpackEmbeddedStatements() {
        Set<Statement> embeddingStatements = new HashSet<>();
        Set<Statement> embeddedStatements = new HashSet<>();

        for (Statement statement : statements) {
            // TODO: unpacked statements do not carry over negation, e.g. "I think ..." and "I don't think ..."
            if (OPINION_PATTERN_1.matches(statement)) {
                for (StatementComponent capture : OPINION_PATTERN_1.getCaptures()) {
                    Statement embeddedStatement = (Statement) capture;
                    embeddedStatement.setOrigin(statement.getOrigin());
                    embeddedStatements.add(embeddedStatement);
                    embeddingStatements.add(statement);
                    logger.info("unpacked " + embeddedStatement + " from " + statement);
                }
            }

            if (OPINION_PATTERN_2.matches(statement)) {
                Set<StatementComponent> opinionComponents = OPINION_PATTERN_2.getNonCaptures(statement);
                Statement embeddedStatement = new Statement(opinionComponents);
                embeddedStatement.setOrigin(statement.getOrigin());
                embeddedStatements.add(embeddedStatement);
                embeddingStatements.add(statement);
                logger.info("unpacked " + embeddedStatement + " from " + statement);
            }
        }

        statements.removeAll(embeddingStatements);
        statements.addAll(embeddedStatements);
        logger.info("total statements unpacked: " + embeddingStatements.size());
    }

    /**
     * Unpack statements according to certain patterns to replace them with their embedded statements.
     */
    private void registerProperNouns() {
        for (Statement statement : statements) {
            if (PROPER_NOUN_PATTERN.matches(statement)) {
                for (StatementComponent capture : PROPER_NOUN_PATTERN.getCaptures()) {
                    AbstractComponent abstractComponent = (AbstractComponent) capture;
                    properNouns.add(abstractComponent.getNormalCompound());
                    logger.info("found proper noun " + abstractComponent + " in " + statement);
                }
            }
        }

        logger.info("total proper nouns found: " + properNouns.size());
    }

    /**
     * Unpack statements according to certain patterns to replace them with their embedded statements.
     */
    private void registerOccupations() {
        for (Statement statement : statements) {
            if (STUDY_PATTERN.matches(statement)) {
                for (StatementComponent capture : STUDY_PATTERN.getCaptures()) {
                    AbstractComponent abstractComponent = (AbstractComponent) capture;
                    studies.add(abstractComponent.getNormalCompound());
                    logger.info("found study " + abstractComponent + " in " + statement);
                }
            }
            if (WORK_PATTERN.matches(statement)) {
                for (StatementComponent capture : WORK_PATTERN.getCaptures()) {
                    AbstractComponent abstractComponent = (AbstractComponent) capture;
                    work.add(abstractComponent.getNormalCompound());
                    logger.info("found work " + abstractComponent + " in " + statement);
                }
            }
        }

        logger.info("total studies found: " + studies.size());
        logger.info("total work found: " + work.size());
    }

    /**
     * Unpack statements according to certain patterns to replace them with their embedded statements.
     */
    private void registerLocations() {
        for (Statement statement : statements) {
            if (LOCATION_PATTERN.matches(statement)) {
                for (StatementComponent capture : LOCATION_PATTERN.getCaptures()) {
                    AbstractComponent abstractComponent = (AbstractComponent) capture;
                    locations.add(abstractComponent.getNormalCompound());
                    logger.info("found location " + abstractComponent + " in " + statement);
                }
            }
        }

        logger.info("total locations found: " + locations.size());
    }

    /**
     * Unpack statements according to certain patterns to replace them with their embedded statements.
     */
    private void registerPossessions() {
        for (Statement statement : statements) {
            if (POSSESSION_PATTERN_1.matches(statement)) {
                for (StatementComponent capture : POSSESSION_PATTERN_1.getCaptures()) {
                    AbstractComponent abstractComponent = (AbstractComponent) capture;
                    possessions.add(abstractComponent.getNormalCompound());
                    logger.info("found possession " + abstractComponent + " in " + statement + " using POSSESSION_PATTERN_1");
                }
            }
            if (POSSESSION_PATTERN_2.matches(statement)) {
                for (StatementComponent capture : POSSESSION_PATTERN_2.getCaptures()) {
                    AbstractComponent abstractComponent = (AbstractComponent) capture;
                    possessions.add(abstractComponent.getNormalCompound());
                    logger.info("found possession " + abstractComponent + " in " + statement + " using POSSESSION_PATTERN_2");
                }
            }
        }

        logger.info("total possessions found: " + possessions.size());
    }

    /**
     * Store the topic keywords of all of the interesting statements.
     */
    private void registerTopics() {
        // register all of the words of the statements
        for (Statement statement : statements) {
            for (StatementComponent component : statement.getComponents()) {
                if (component instanceof AbstractComponent) {
                    AbstractComponent abstractComponent = (AbstractComponent) component;
                    if (component instanceof Subject) subjectWords.add(abstractComponent.getBasicCompound());
                    // TODO: use verbs (excluding most common ones)?
//                    if (component instanceof Verb) verbWords.add(abstractComponent.getBasicCompound());
                    if (component instanceof DirectObject) directObjectWords.add(abstractComponent.getBasicCompound());
                    if (component instanceof IndirectObject) indirectObjectWords.add(abstractComponent.getBasicCompound());
                }
            }
        }

        topics.addAll(subjectWords);
        topics.addAll(verbWords);
        topics.addAll(directObjectWords);
        topics.addAll(indirectObjectWords);
    }

    /**
     * Get the statements that evaluate as interesting according to their own internal measure.
     *
     * @return interesting statements
     */
    public Set<Statement> getInterestingStatements() {
        if (interestingStatements.isEmpty()) {
            for (Statement statement : getStatements()) {
                if (INTERESTING_PATTERN.matches(statement) && !INTERESTING_ANTIPATTERN_1.matches(statement)) interestingStatements.add(statement);
            }
        }

        return interestingStatements;
    }

    /**
     * Returns the topics that are shared between two profiles.
     * Useful for adjusting the ranking of statements.
     *
     * @param otherProfile the other profile to compare topics with
     * @return the topics found in both profiles
     */
    public Set<String> findSharedTopics(Profile otherProfile) {
        Set<String> sharedTopics = new HashSet<>(getTopics());
        sharedTopics.retainAll(otherProfile.getTopics());
        return sharedTopics;
    }

    /**
     * Returns statements in order of diminishing lexical density.
     *
     * @return statements
     */
    public List<Statement> getStatementsByLexicalDensity() {
        List<Statement> rankedStatements = new ArrayList<>(getInterestingStatements());
        rankedStatements.sort(new LexicalDensityComparator());
        return rankedStatements;
    }

    /**
     * Returns statements in order of diminishing quality.
     *
     * @return statements
     */
    public List<Statement> getStatementsByQuality() {
        List<Statement> rankedStatements = new ArrayList<>(getInterestingStatements());
        rankedStatements.sort(new QualityComparator());
        return rankedStatements;
    }

    /**
     * Returns statements in order of diminishing relevance from the perspective of another profile.
     *
     * @param otherProfile the profile to measure relevance against
     * @return relevant statements
     */
    public List<Statement> getStatementsByRelevance(Profile otherProfile) {
        List<Statement> rankedStatements = new ArrayList<>(getInterestingStatements());
        rankedStatements.sort(new RelevanceComparator(otherProfile));
        return rankedStatements;
    }

    public Set<Statement> getStatements() {
        return statements;
    }

    public Set<String> getTopics() {
        return topics;
    }

    public Set<String> getLocations() {
        return locations;
    }

    public Set<String> getPossessions() {
        return possessions;
    }

    public Set<String> getStudies() {
        return studies;
    }

    public Set<String> getWork() {
        return work;
    }

    public Set<String> getProperNouns() {
        return properNouns;
    }

    /**
     * Whether or not the statement contains one of the topics of the profile.
     *
     * @param statement the statement to check
     * @return true if the statement contains a topic
     */
    private boolean containsTopics(Statement statement) {
        for (StatementComponent component : statement.getComponents()) {
            if (component instanceof AbstractComponent) {
                AbstractComponent abstractComponent = (AbstractComponent) component;
                String basicCompound = abstractComponent.getBasicCompound();
                if (getTopics().contains(basicCompound)) return true;
            }
        }

        return false;
    }

    /**
     * Calculates the quality for a statement.
     * Used for rankings statements.
     *
     * @param statement statement to assess
     * @return quality of statement
     */
    private double getStatementQuality(Statement statement) {
        if (!qualityMap.containsKey(statement)) {
            // retrieve the baseline value, in this case lexical density
            double quality = statement.getLexicalDensity();
            double step = quality * 0.2;

            // adjust for personal information
            if (PERSONAL_PATTERN.matches(statement)) {
                quality += step;
            }

            // save to map for later lazy-loading
            qualityMap.put(statement, quality);

            return quality;
        } else {
            return qualityMap.get(statement);
        }
    }

    /**
     * Used to sort Statements by lexical density.
     */
    public static class LexicalDensityComparator implements Comparator<Statement> {
        @Override
        public int compare(Statement x, Statement y) {
            double xn = x.getLexicalDensity();
            double yn = y.getLexicalDensity();

            if (xn == yn) {
                return 0;
            } else {
                if (xn > yn) {
                    return -1;
                } else {
                    return 1;
                }
            }
        }
    }

    /**
     * Used to sort Statements by quality.
     */
    public class QualityComparator implements Comparator<Statement> {
        @Override
        public int compare(Statement x, Statement y) {
            double xn = getStatementQuality(x);
            double yn = getStatementQuality(y);

            if (xn == yn) {
                return 0;
            } else {
                if (xn > yn) {
                    return -1;
                } else {
                    return 1;
                }
            }
        }
    }

    /**
     * Used to sort Statements by quality relative to other profiles.
     */
    public class RelevanceComparator implements Comparator<Statement> {
        private final Profile otherProfile;

        public RelevanceComparator(Profile otherProfile) {
            this.otherProfile = otherProfile;
        }

        @Override
        public int compare(Statement x, Statement y) {
            // retrieve the baseline values, in this case lexical density
            double xn = x.getLexicalDensity();
            double yn = y.getLexicalDensity();

            // TODO: the rest

            if (xn == yn) {
                return 0;
            } else {
                if (xn > yn) {
                    return -1;
                } else {
                    return 1;
                }
            }
        }
    }

    public String getStatementInfo(Statement statement) {
        return "{" +
                statement.getSummary() +
                ": \"" + statement.getSentence() + "\"" +
                ", density: " + df.format(statement.getLexicalDensity()) +
                ", quality: " + df.format(qualityMap.get(statement)) +
//                ", relevance: " + df.format(relevanceMap.get(statement)) +
                "}" + " " + statement.getOrigin();
    }
}
