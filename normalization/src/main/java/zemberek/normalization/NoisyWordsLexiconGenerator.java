package zemberek.normalization;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.Token;
import zemberek.core.IntPair;
import zemberek.core.collections.Histogram;
import zemberek.core.collections.IntIntMap;
import zemberek.core.collections.IntVector;
import zemberek.core.collections.UIntMap;
import zemberek.core.collections.UIntSet;
import zemberek.core.collections.UIntValueMap;
import zemberek.core.concurrency.BlockingExecutor;
import zemberek.core.io.IOUtil;
import zemberek.core.logging.Log;
import zemberek.core.text.TextIO;
import zemberek.core.text.TextUtil;
import zemberek.core.text.distance.CharDistance;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.normalization.NormalizationVocabularyGenerator.Vocabulary;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;
import zemberek.tokenization.antlr.TurkishLexer;

/**
 * A modified implementation of Hassan and Menezes's 2013 paper "Social Text Normalization using
 * Contextual Graph Random Walks".
 * <p></p>
 * Algorithm basically works like this:
 * <p></p>
 * First, we need to have two vocabularies from a corpus. One vocabulary is correct words, other is
 * noisy words. This operation is actually quite tricky as how to decide if a word is noisy is not
 * easy. For Turkish we use morphological analysis but it may actually fail for some proper nouns
 * and for inputs where Turkish characters are not used.
 * <p></p>
 * Second, a bipartite graph is generated from the corpus. There are two sides in the graph. One
 * represents contexts, other represents words. For example:
 * <pre>
 *  context(bu * eve) -> (sabah:105, zabah:3, akşam:126, aksam:7, mavi:2 ...)
 *  context(sabah * geldim) -> (eve:56, işe:78, okula:64, okulua:2 ...)
 *  word(sabah) -> ([bu eve]:105, [bu kahvaltıda]:23, [her olmaz]:7 ...)
 *  word(zabah) -> ([bu eve]:3 ...)
 * </pre>
 * (bu * eve) represents a context. And sabah:105 means from this context, "sabah" appeared in the
 * middle 105 times. And noisy "zabah" appeared 3 times.
 * <p></p>
 * Here we do something different from original paper, when building contextual similarity graph, we
 * use 32 bit hash values of the contexts instead of the contexts itself. This reduces memory and
 * calculation cost greatly.
 * <p></p>
 * After this graph is constructed, For every noisy word in the graph several random walks are done
 * as below:
 * <pre>
 * - Start from a noisy word.
 * - repeat k times (such as k = 100)
 * -- Select one of the contexts of this word randomly. But, random is not uniform.
 *   Context is selected proportional to occurrence counts.
 * -- From context, similarly randomly hop to a word.
 * -- If word is noisy, continue hops.
 * -- If word is not noisy or hop count reaches to a certain value (such as 4), stop.
 *    Store average-hitting-time
 * - Calculate contextual and lexical similarity and prune. Lexical similarity is calculated
 *   with modified edit distance and longest common substring ratio (from Contractor et al. 2010
 *   Unsupervised cleansing of noisy text)
 *
 * </pre>
 * If random walks are repeated for many times, All candidates that may be the correct version can
 * be collected. After that, a Viterbi search using a language model can be performed for better
 * accuracy.
 */
public class NoisyWordsLexiconGenerator {


  public static void main(String[] args) throws Exception {

    NoisyWordsLexiconGenerator generator = new NoisyWordsLexiconGenerator();

    Path corporaRoot = Paths.get("/media/aaa/Data/corpora/reduced");
    Path outRoot = Paths.get("/home/aaa/data/normalization/test-small");
    Path rootList = corporaRoot.resolve("corpora.list.small");
    List<String> rootNames = TextIO.loadLines(rootList, "#");

    List<Path> roots = new ArrayList<>();
    rootNames.forEach(s -> roots.add(corporaRoot.resolve(s)));

    List<Path> corpora = new ArrayList<>();
    for (Path corpusRoot : roots) {
      corpora.addAll(Files.walk(corpusRoot, 1)
          .filter(s -> s.toFile().isFile())
          .collect(Collectors.toList()));
    }

    Log.info("There are %d corpus files.", corpora.size());

    Files.createDirectories(outRoot);

    Path correct = outRoot.resolve("correct");
    Path incorrect = outRoot.resolve("incorrect");

    NormalizationVocabulary vocabulary = new NormalizationVocabulary(
        correct, incorrect, 1, 3
    );

    // create graph
    Path graphPath = outRoot.resolve("graph");
    //ContextualSimilarityGraph graph = getGraph(generator, corpora, vocabulary, graphPath);

    // create Random Walker
    Log.info("Constructing random walk graph from %s", graphPath);
    RandomWalker walker = RandomWalker.fromGraphFile(vocabulary, graphPath);

    Log.info("Collecting candidates data.");
    int threadCount = Runtime.getRuntime().availableProcessors() / 2;
    WalkResult walkResult = walker.walk(1000, 10, threadCount);
    Path allCandidates = outRoot.resolve("all-candidates");

    Histogram<String> noisyWords = Histogram.loadFromUtf8File(incorrect, ' ');

    try (PrintWriter pw = new PrintWriter(allCandidates.toFile(), "utf-8")) {
      List<String> words = new ArrayList<>(walkResult.allCandidates.keySet());

      words.sort((a, b) -> Integer.compare(noisyWords.getCount(b), noisyWords.getCount(a)));

      for (String s : words) {
        List<WalkScore> scores = new ArrayList<>(walkResult.allCandidates.get(s));
        float lambda1 = 1f;
        float lambda2 = 1f;
        scores.sort(
            (a, b) -> Float.compare(b.getScore(lambda1, lambda2), a.getScore(lambda1, lambda2)));
        scores = scores.stream()
            .filter(w -> w.getScore(lambda1, lambda2) >= 0.25)
            .collect(Collectors.toList());
        pw.println(s);
        for (WalkScore score : scores) {
          pw.println(String.format("%s:%.3f (%.3f - %.3f)",
              score.candidate, score.getScore(lambda1, lambda2),
              score.contextualSimilarity,
              score.lexicalSimilarity));
        }
        pw.println();
      }
    }

    Log.info("Done");
  }

  private static ContextualSimilarityGraph getGraph(NoisyWordsLexiconGenerator generator,
      List<Path> corpora, NormalizationVocabulary vocabulary, Path graphPath) throws IOException {
    ContextualSimilarityGraph graph = generator.buildGraph(vocabulary, corpora, 1);
    Log.info("Context hash count = %d", graph.contextHashCount());
    Log.info("Edge count = %d", graph.edgeCount());

    Log.info("Serializing graph for random walk structure.");
    graph.serializeForRandomWalk(graphPath);
    Log.info("Serialized to %s", graphPath);
    return graph;
  }

  /**
   * Generates a bipartite graph that represents contextual similarity.
   */
  ContextualSimilarityGraph buildGraph(
      NormalizationVocabulary vocabulary,
      List<Path> corpora,
      int contextSize) throws IOException {

    ContextualSimilarityGraph graph = new ContextualSimilarityGraph(vocabulary, contextSize);

    int i = 0;
    for (Path path : corpora) {
      LinkedHashSet<String> sentences = getSentences(path);
      for (String sentence : sentences) {
        graph.add(sentence);
        if (i % 100_000 == 0) {
          Log.info("Sentence processed : " + i);
        }
        i++;
      }
    }
    Log.info("contexts = " + graph.contextHashToWordCounts.size());
    graph.pruneContextNodes();
    Log.info("After pruning contexts = " + graph.contextHashToWordCounts.size());
    graph.createWordToContextMap();
    return graph;
  }

  private LinkedHashSet<String> getSentences(Path path) throws IOException {
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
        .filter(s -> !s.startsWith("<"))
        .map(TextUtil::normalizeSpacesAndSoftHyphens)
        .collect(Collectors.toList());
    return new LinkedHashSet<>(TurkishSentenceExtractor.DEFAULT.fromParagraphs(lines));
  }

  private static final String SENTENCE_START = "<s>";
  private static final String SENTENCE_END = "</s>";

  static class RandomWalkNode {

    int[] keysCounts;
    int totalCounts;

    RandomWalkNode(int[] keysCounts, int totalCounts) {
      this.keysCounts = keysCounts;
      this.totalCounts = totalCounts;
    }
  }

  static class RandomWalker {

    UIntMap<RandomWalkNode> contextHashesToWords;
    UIntMap<RandomWalkNode> wordsToContextHashes;
    NormalizationVocabulary vocabulary;
    private static final Random rnd = new Random(1);

    RandomWalker(
        NormalizationVocabulary vocabulary,
        UIntMap<RandomWalkNode> contextHashesToWords,
        UIntMap<RandomWalkNode> wordsToContextHashes) {
      this.vocabulary = vocabulary;
      this.contextHashesToWords = contextHashesToWords;
      this.wordsToContextHashes = wordsToContextHashes;
    }

    static RandomWalker fromGraphFile(NormalizationVocabulary vocabulary, Path path)
        throws IOException {
      try (DataInputStream dis = IOUtil.getDataInputStream(path)) {
        UIntMap<RandomWalkNode> contextHashesToWords = loadNodes(dis);
        UIntMap<RandomWalkNode> wordsToContextHashes = loadNodes(dis);
        return new RandomWalker(vocabulary, contextHashesToWords, wordsToContextHashes);
      }
    }

    private static UIntMap<RandomWalkNode> loadNodes(DataInputStream dis) throws IOException {
      int nodeCount = dis.readInt();
      Log.info("There are %d nodes.", nodeCount);
      UIntMap<RandomWalkNode> edgeMap = new UIntMap<>(nodeCount / 2);
      for (int i = 0; i < nodeCount; i++) {
        int key = dis.readInt();
        int size = dis.readInt();
        int[] keysCounts = new int[size * 2];
        int totalCount = 0;
        for (int j = 0; j < size * 2; j++) {
          int val = dis.readInt();
          keysCounts[j] = val;
          if ((j & 0x01) == 1) {
            totalCount += val;
          }
        }
        edgeMap.put(key, new RandomWalkNode(keysCounts, totalCount));
        if (i > 0 && i % 500_000 == 0) {
          Log.info("%d node data loaded.", i);
        }
      }
      return edgeMap;
    }

    WalkResult walk(int walkCount, int maxHopCount, int threadCount)
        throws Exception {
      List<Work> workList = new ArrayList<>();
      int batchSize = 5_000;
      IntVector vector = new IntVector(batchSize);
      for (int wordIndex : wordsToContextHashes.getKeys()) {
        // only noisy words
        if (vocabulary.isValid(wordIndex)) {
          continue;
        }
        vector.add(wordIndex);
        if (vector.size() == batchSize) {
          workList.add(new Work(vector.copyOf()));
          vector = new IntVector(batchSize);
        }
      }
      // for remaining data.
      if (vector.size() > 0) {
        workList.add(new Work(vector.copyOf()));
      }

      ExecutorService executorService = new BlockingExecutor(threadCount);
      CompletionService<WalkResult> service =
          new ExecutorCompletionService<>(executorService);

      for (Work work : workList) {
        service.submit(new WalkTask(
            this,
            work,
            walkCount,
            maxHopCount
        ));
      }
      executorService.shutdown();

      int workCount = 0;
      WalkResult result = new WalkResult();
      while (workCount < workList.size()) {
        WalkResult threadResult = service.take().get();
        result.allCandidates.putAll(threadResult.allCandidates);
        Log.info("%d words processed.", result.allCandidates.keySet().size());
        workCount++;
      }
      return result;
    }

    /**
     * From a node, selects a connected node randomly. Randomness is not uniform, it is proportional
     * to the occurrence counts attached to the edges.
     */
    int selectFromDistribution(RandomWalkNode node) {
      int dice = rnd.nextInt(node.totalCounts + 1);
      int accumulator = 0;
      int[] keysCounts = node.keysCounts;
      for (int i = 0; i < keysCounts.length; i += 2) {
        accumulator += keysCounts[i + 1];
        if (accumulator >= dice) {
          return keysCounts[i];
        }
      }
      throw new IllegalStateException("Unreachable.");
    }
  }

  static class WalkResult {

    Multimap<String, WalkScore> allCandidates = HashMultimap.create();
  }

  static class WalkScore {

    String candidate;
    int hitCount;
    int hopCount;
    float contextualSimilarity;
    float lexicalSimilarity;

    void update(int hopeCount) {
      this.hitCount++;
      this.hopCount += hopeCount;
    }

    float getAverageHittingTime() {
      return hopCount * 1f / hitCount;
    }

    float getScore() {
      return contextualSimilarity + lexicalSimilarity;
    }

    float getScore(float lambda1, float lambda2) {
      return lambda1 * contextualSimilarity + lambda2 * lexicalSimilarity;
    }


    public WalkScore(String candidate) {
      this.candidate = candidate;
    }


    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      WalkScore walkScore = (WalkScore) o;

      return candidate.equals(walkScore.candidate);
    }

    @Override
    public int hashCode() {
      return candidate.hashCode();
    }
  }

  private static class Work {

    int[] wordIndexes;

    Work(int[] wordIndexes) {
      this.wordIndexes = wordIndexes;
    }
  }

  private static class WalkTask implements Callable<WalkResult> {

    RandomWalker walker;
    Work work;
    int walkCount;
    int maxHopCount;

    WalkTask(RandomWalker walker, Work work, int walkCount, int maxHopCount) {
      this.walker = walker;
      this.work = work;
      this.walkCount = walkCount;
      this.maxHopCount = maxHopCount;
    }

    @Override
    public WalkResult call() {
      WalkResult result = new WalkResult();
      CharDistance distanceCalculator = new CharDistance();
      for (int wordIndex : work.wordIndexes) {
        // Only noisy words. Check anyway, to be sure.
        if (walker.vocabulary.isValid(wordIndex)) {
          continue;
        }

        Map<String, WalkScore> scores = new HashMap<>();

        for (int i = 0; i < walkCount; i++) {
          int nodeIndex = wordIndex;
          boolean atWordNode = true;
          for (int j = 0; j < maxHopCount; j++) {

            RandomWalkNode node = atWordNode ?
                walker.wordsToContextHashes.get(nodeIndex) :
                walker.contextHashesToWords.get(nodeIndex);

            nodeIndex = walker.selectFromDistribution(node);

            atWordNode = !atWordNode;

            // if we reach to a valid word ([...] --> [Context node] --> [Valid word node] )
            if (atWordNode && nodeIndex != wordIndex && walker.vocabulary.isValid(nodeIndex)) {
              String word = walker.vocabulary.getWord(nodeIndex);
              WalkScore score = scores.get(word);
              if (score == null) {
                score = new WalkScore(word);
                scores.put(word, score);
              }
              score.update(j + 1);
              break;
            }
          }
        }

        // calculate contextual similarity probabilities.
        float totalAverageHittingTime = 0;
        for (WalkScore score : scores.values()) {
          totalAverageHittingTime += score.getAverageHittingTime();
        }
        for (String s : scores.keySet()) {
          WalkScore score = scores.get(s);
          score.contextualSimilarity =
              score.getAverageHittingTime() / totalAverageHittingTime;
        }

        // calculate lexical similarity cost. This is slow for now.
        String word = walker.vocabulary.getWord(wordIndex);
        // convert to ascii and remove vowels and repetitions.
        String reducedSource = reduceWord(word);

        for (String s : scores.keySet()) {
          String reducedTarget = reduceWord(s);
          float editDistance =
              (float) distanceCalculator.distance(reducedSource, reducedTarget) + 1;

          // longest commons substring ratio
          float lcsr = longestCommonSubstring(word, s).length() * 1f /
              Math.max(s.length(), word.length());

          WalkScore score = scores.get(s);
          score.lexicalSimilarity = lcsr / editDistance;
        }
        result.allCandidates.putAll(word, scores.values());
      }
      return result;
    }
  }

  static Map<String, String> reducedWords = new ConcurrentHashMap<>(100_000);

  static String reduceWord(String input) {

    String cached = reducedWords.get(input);
    if (cached != null) {
      return cached;
    }
    String s = TurkishAlphabet.INSTANCE.toAscii(input);
    if (input.length() < 3) {
      return s;
    }
    StringBuilder sb = new StringBuilder(input.length() - 2);
    char previous = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (TurkishAlphabet.INSTANCE.isVowel(c) || c == 'ğ') {
        previous = 0;
        continue;
      }
      if (previous == c) {
        continue;
      }
      sb.append(c);
      previous = c;
    }
    String reduced = sb.toString();

    if (reduced.length() == 0) {
      return input;
    }

    reducedWords.put(input, reduced);
    return reduced;
  }

  static class NormalizationVocabulary {

    List<String> words;
    UIntValueMap<String> indexes = new UIntValueMap<>();
    int outWordStart;

    NormalizationVocabulary(
        Vocabulary vocabulary,
        int correctMinCount,
        int incorrectMinCount) {
      Histogram<String> inWords = vocabulary.correct;
      Histogram<String> outWords = vocabulary.incorrect;
      inWords.removeSmaller(correctMinCount);
      outWords.removeSmaller(incorrectMinCount);
      this.outWordStart = inWords.size();
      this.words = new ArrayList<>(inWords.getSortedList());
      words.addAll(outWords.getSortedList());
      int i = 0;
      for (String word : words) {
        indexes.put(word, i);
        i++;
      }
    }

    NormalizationVocabulary(
        Path correct,
        Path incorrect,
        int correctMinCount,
        int incorrectMinCount) throws IOException {
      this(new Vocabulary(
              Histogram.loadFromUtf8File(correct, ' '),
              Histogram.loadFromUtf8File(incorrect, ' '),
              new Histogram<>()),
          correctMinCount, incorrectMinCount);
    }

    int totalSize() {
      return words.size();
    }

    boolean isValid(int id) {
      return id >= 0 && id < outWordStart && id < totalSize();
    }

    boolean isValid(String id) {
      return isValid(getIndex(id));
    }

    int getIndex(String word) {
      return indexes.get(word);
    }

    String getWord(int id) {
      return words.get(id);
    }
  }

  class ContextualSimilarityGraph {

    UIntMap<IntIntMap> contextHashToWordCounts = new UIntMap<>();
    UIntMap<IntIntMap> wordToContexts = new UIntMap<>();

    NormalizationVocabulary vocabulary;

    int contextSize;

    ContextualSimilarityGraph(
        NormalizationVocabulary vocabulary,
        int contextSize) {

      if (contextSize < 1 || contextSize > 2) {
        throw new IllegalArgumentException("Context must be 1 or 2 but it is " + contextSize);
      }
      this.contextSize = contextSize;
      this.vocabulary = vocabulary;
    }

    void add(String sentence) {

      List<String> tokens = new ArrayList<>();
      for (int i = 0; i < contextSize; i++) {
        tokens.add(SENTENCE_START);
      }
      sentence = sentence.toLowerCase(Turkish.LOCALE);
      List<Token> raw = TurkishTokenizer.DEFAULT.tokenize(sentence);
      for (Token token : raw) {
        if (token.getType() == TurkishLexer.Punctuation) {
          continue;
        }
        String text = token.getText();
        if (token.getType() == TurkishLexer.Time ||
            token.getType() == TurkishLexer.PercentNumeral ||
            token.getType() == TurkishLexer.Number ||
            token.getType() == TurkishLexer.Date) {
          text = text.replaceAll("[0-9]+", "_d");
        } else if (token.getType() == TurkishLexer.URL) {
          text = "<url>";
        } else if (token.getType() == TurkishLexer.HashTag) {
          text = "<hashtag>";
        } else if (token.getType() == TurkishLexer.Email) {
          text = "<email>";
        }

        tokens.add(text);
      }

      for (int i = 0; i < contextSize; i++) {
        tokens.add(SENTENCE_END);
      }
      String[] context = new String[contextSize * 2];
      for (int i = contextSize; i < tokens.size() - contextSize; i++) {
        // if current word is out of vocabulary, continue.
        int wordIndex = vocabulary.getIndex(tokens.get(i));
        if (wordIndex == -1) {
          continue;
        }

        // gather context and calculate hash
        if (contextSize == 1) {
          context[0] = tokens.get(i - 1);
          context[1] = tokens.get(i + 1);
        } else {
          context[0] = tokens.get(i - 2);
          context[1] = tokens.get(i - 1);
          context[2] = tokens.get(i + 1);
          context[3] = tokens.get(i + 2);
        }
        int hash = hash(context);

        // update context -> word counts
        IntIntMap wordCounts = contextHashToWordCounts.get(hash);
        if (wordCounts == null) {
          wordCounts = new IntIntMap(1);
          contextHashToWordCounts.put(hash, wordCounts);
        }
        wordCounts.increment(wordIndex, 1);
      }
    }

    void pruneContextNodes() {
      UIntSet keysToPrune = new UIntSet();
      for (int contextHash : contextHashToWordCounts.getKeys()) {
        IntIntMap m = contextHashToWordCounts.get(contextHash);

        // prune if a context appears only once.
        if (m.size() <= 1) {
          keysToPrune.add(contextHash);
          continue;
        }

        // prune if a context is only connected to noisy words. For speed we only check nodes with
        // at most five connections.
        if (m.size() < 5) {
          int noisyCount = 0;
          for (int wordIndex : m.getKeys()) {
            if (!vocabulary.isValid(wordIndex)) {
              noisyCount++;
            } else {
              break;
            }
          }
          if (noisyCount == m.size()) {
            keysToPrune.add(contextHash);
          }
        }
      }
      for (int keyToRemove : keysToPrune.getKeys()) {
        contextHashToWordCounts.remove(keyToRemove);
      }
    }

    void createWordToContextMap() {
      for (int contextHash : contextHashToWordCounts.getKeys()) {
        IntIntMap m = contextHashToWordCounts.get(contextHash);
        for (int worIndex : m.getKeys()) {
          int count = m.get(worIndex);
          IntIntMap contextCounts = wordToContexts.get(worIndex);
          if (contextCounts == null) {
            contextCounts = new IntIntMap(1);
            wordToContexts.put(worIndex, contextCounts);
          }
          contextCounts.put(contextHash, count);
        }
      }
    }

    void serializeForRandomWalk(Path p) throws IOException {
      try (DataOutputStream dos = IOUtil.getDataOutputStream(p)) {
        serializeEdges(dos, contextHashToWordCounts);
        serializeEdges(dos, wordToContexts);
      }
    }

    private void serializeEdges(DataOutputStream dos, UIntMap<IntIntMap> edgesMap)
        throws IOException {

      dos.writeInt(edgesMap.size());

      for (int nodeIndex : edgesMap.getKeys()) {
        dos.writeInt(nodeIndex);
        IntIntMap map = edgesMap.get(nodeIndex);
        if (map == null) {
          throw new IllegalStateException("edge map is null!");
        }
        dos.writeInt(map.size());
        IntPair[] pairs = map.getAsPairs();
        Arrays.sort(pairs, (a, b) -> Integer.compare(b.second, a.second));
        for (IntPair pair : pairs) {
          dos.writeInt(pair.first);
          dos.writeInt(pair.second);
        }
      }
    }

    int contextHashCount() {
      return contextHashToWordCounts.size();
    }

    long edgeCount() {
      long i = 0;
      for (IntIntMap m : contextHashToWordCounts) {
        i += m.size();
      }
      return i;
    }
  }

  static int hash(String... context) {
    int d = 0x811C9DC5;
    for (String s : context) {
      for (int i = 0; i < s.length(); i++) {
        d = (d ^ s.charAt(i)) * 16777619;
      }
    }
    return d & 0x7fffffff;
  }

  /**
   * Finds the longest common substring of two strings.
   */
  static String longestCommonSubstring(String a, String b) {
    int[][] lengths = new int[a.length() + 1][b.length() + 1];

    // row 0 and column 0 are initialized to 0 already

    for (int i = 0; i < a.length(); i++) {
      for (int j = 0; j < b.length(); j++) {
        if (a.charAt(i) == b.charAt(j)) {
          lengths[i + 1][j + 1] = lengths[i][j] + 1;
        } else {
          lengths[i + 1][j + 1] =
              Math.max(lengths[i + 1][j], lengths[i][j + 1]);
        }
      }
    }

    // read the substring out from the matrix
    StringBuilder sb = new StringBuilder();
    for (int x = a.length(), y = b.length();
        x != 0 && y != 0; ) {
      if (lengths[x][y] == lengths[x - 1][y]) {
        x--;
      } else if (lengths[x][y] == lengths[x][y - 1]) {
        y--;
      } else {
        assert a.charAt(x - 1) == b.charAt(y - 1);
        sb.append(a.charAt(x - 1));
        x--;
        y--;
      }
    }

    return sb.reverse().toString();
  }

}
