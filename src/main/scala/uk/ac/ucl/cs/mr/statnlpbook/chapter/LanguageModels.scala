package uk.ac.ucl.cs.mr.statnlpbook.chapter

import cc.factorie.variable.DenseProportions1
import gnu.trove.map.hash.TIntDoubleHashMap
import ml.wolfe.nlp.Document
import ml.wolfe.term._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Random

import uk.ac.ucl.cs.mr.statnlpbook.corpora.OHHLA
import uk.ac.ucl.cs.mr.statnlpbook.Util._


/**
 * @author riedel
 */
object LanguageModels {

  type History = List[String]
  type ConditionalCounts = PartialFunction[History, Counts]
  type Vocab = Set[String]

  val random = new Random(0)

  trait Counts extends (String => Double) {

    def vocab: Vocab

    def apply(word: String): Double

    def inverse(count: Double): List[String]

    def activeCounts: List[Double]

    def normalizer: Double

    def prob(word: String) =
      this(word) / normalizer

    lazy val indexedVocab = vocab.toIndexedSeq
    lazy val proportions = new DenseProportions1((indexedVocab map prob).toArray)

    def sample() = {
      val index = proportions.sampleIndex(random)
      indexedVocab(index)
    }

    def asMap = vocab.map(w => w -> apply(w)).toMap

    override def toString() = asMap.toString()
  }

  class LaplaceCounts(val original: Counts, val alpha: Double) extends Counts {

    def apply(word: String): Double = original(word) + alpha

    def inverse(count: Double) = original.inverse(count - alpha)

    def vocab = original.vocab

    lazy val activeCounts = original.activeCounts map (_ + alpha)

    lazy val normalizer = original.normalizer + vocab.size * alpha

    override def toString() = s"$original + $alpha"
  }

  class RawCounts(val counts: Map[String, Double], val vocab: Vocab) extends Counts {

    def apply(word: String) = counts.getOrElse(word, 0.0)

    def inverse(count: Double) = inverseMap(count)

    lazy val activeCounts = 0.0 :: counts.values.toList
    lazy val inverseMap = counts.groupBy(_._2) mapValues (_.keys.toList)
    lazy val normalizer = counts.valuesIterator.sum

    override def toString() = counts.toString()

  }

  trait DiscountedCounts extends Counts {
    def original: Counts

    def discounts: Map[Double, Double]

    def apply(word: String) = discounts(original(word))

    lazy val inverseDiscounts = discounts.groupBy(_._2) mapValues (_.keys.toList)

    def inverse(count: Double) = inverseDiscounts(count) flatMap original.inverse

    lazy val activeCounts = original.activeCounts map (c => discounts.getOrElse(c, c))

    lazy val normalizer = {
      val old = original.normalizer
      old - discounts.keys.map(count => inverse(count).length * (count + discounts(count))).sum
    }
  }

  def vocabLM(vocabulary: Vocab) = {
    new NGramLM {
      val counts = Map(List.empty[String] -> constantCounts(vocabulary))

      def historySize = 0

      def vocab = vocabulary
    }
  }

  def constantCounts(vocabulary: Vocab) = new LaplaceCounts(zeroCounts(vocabulary), 1.0)

  def ngramLM(train: History, historyLength: Int, vocabulary: Vocab): NGramLM = {
    val counts = new mutable.HashMap[History, mutable.HashMap[String, Double]]
    @tailrec
    def recurse(data: History): Unit = data match {
      case head :: tail if tail.take(historyLength).length == historyLength =>
        if (vocabulary(head)) {
          val history = tail.take(historyLength)
          val map = counts.getOrElseUpdate(history, new mutable.HashMap[String, Double])
          map(head) = map.getOrElse(head, 0.0) + 1.0
        }
        recurse(tail)
      case _ =>
    }
    recurse(padded(train, historyLength))
    val map = counts mapValues (c => new RawCounts(c.toMap, vocabulary))
    lm(map.toMap, vocabulary, historyLength)
  }

  def lm(conditionalCounts: ConditionalCounts, vocabulary: Vocab, history: Int) = new NGramLM {
    def historySize = history

    def counts = conditionalCounts

    def vocab = vocabulary
  }

  def zeroCounts(vocabulary: Vocab) = new Counts {
    def activeCounts = 0.0 :: Nil

    def inverse(count: Double) = if (count == 0.0) vocab.toList else Nil

    def apply(word: String) = 0.0

    def vocab = vocabulary

    def normalizer = 0.0

    override def toString() = "_ => 0.0"
  }

  def laplace(lm: NGramLM, addCount: Double) =
    lm decorate { c => cached(c andThen (new LaplaceCounts(_, addCount))) }

  def backOff(lmN: NGramLM, lmNMinus1: NGramLM) =
    lmN decorate { c => c orElse { case h => lmNMinus1.counts(h.dropRight(1)) } }

  def interpolate(lmN: NGramLM, lmNMinus1: NGramLM, alpha: Double) =
    lmN decorate { c => c orElse { case h => lmNMinus1.counts(h.dropRight(1)) } }


  //for each history we need to know which values are defined
  //type Counts = PartialFunction[String,Double]
  //PartialFunction[History,PartialFunction[String,Double]]

  trait NGramLM {
    self =>

    def counts: ConditionalCounts

    def vocab: Vocab

    lazy val indexedVocab = vocab.toIndexedSeq

    def historySize: Int

    lazy val padding = (0 until historySize).toList map (_ => PAD)

    def decorate(f: ConditionalCounts => ConditionalCounts) = new LMDecorator {
      def original = self

      lazy val counts = f(self.counts)
    }

    @tailrec
    final def sampleMany(numSamples: Int, soFar: History = padding): History = {
      require(soFar.length >= historySize)
      numSamples match {
        case 0 => soFar
        case n =>
          val history = soFar.take(historySize)
          val next = sample(history)
          sampleMany(n - 1, next :: soFar)
      }
    }

    @tailrec
    final def logPerplexity(data: History, result: Double = 0.0): Double = {
      require(data.length >= historySize)
      data match {
        case d if d.length == historySize =>
          result
        case head :: tail if vocab(head) =>
          val p = prob(head, tail.take(historySize))
          val l = math.log(p)
          logPerplexity(tail, result + l)
        case head :: tail =>
          logPerplexity(tail, result)
      }
    }

    def perplexity(data: History) = {
      val logP = logPerplexity(padded(data, historySize))
      val normed = -logP / data.length
      math.exp(normed)
    }

    def prob(word: String, history: History) =
      counts(history).prob(word)

    def sample(history: History) = {
      counts(history).sample()
    }


  }

  trait LMDecorator extends NGramLM {
    def original: NGramLM

    def historySize = original.historySize

    def vocab = original.vocab
  }

  def history(docs: Iterable[Document], padding: Int = 5) = {
    val content = docs flatMap (_.tokens map (_.word))
    val list = content.toList
    val result = list.reverse
    result
  }

  @tailrec
  def replaceFirstOccurenceWithOOV(oov: String,
                                   toProcess: History,
                                   result: History = Nil,
                                   vocab: Vocab = Set(PAD)): History = {
    toProcess match {
      case Nil => result
      case head :: tail =>
        val (word, newVocab) = if (vocab(head)) (head, vocab) else (oov, vocab + head)
        replaceFirstOccurenceWithOOV(oov, tail, word :: result, newVocab)
    }
  }

  def padded(history: History, howMuch: Int = 5) = {
    val init = (0 until howMuch).toList map (_ => PAD)
    history ++ init
  }


  def filterByVocab(vocab: Vocab, oov: String, corpus: History) =
    corpus map (w => if (vocab(w)) w else oov)

  def OOV = "<OOV>"

  def PAD = "<PAD>"

  def main(args: Array[String]) {
    //when calculating perplexity and training a model, the input should always be padded
    //but OOV after padding creates: [PAD][OOV][OOV] ...
    val docs = OHHLA.JLive.allAlbums flatMap OHHLA.loadDir
    val (trainDocs, testDocs) = docs.splitAt(docs.length - 1)
    val train = replaceFirstOccurenceWithOOV(OOV, history(trainDocs)).reverse
    val vocab = train.toSet
    val test = filterByVocab(vocab, OOV, history(testDocs)).reverse

    println(train.length)

    //    println(words(OHHLA.loadAll(OHHLA.jLiveAllAbove)).take(100).mkString("\n"))
    //    println("---")
    //    println(test.take(10).mkString("\n"))

    for (_ <- 0 until 1) {
      val lms = Seq(
        "vocabLM" -> vocabLM(vocab),
        "unigramLM" -> ngramLM(train, 0, vocab),
        "bigramLM" -> laplace(ngramLM(train, 1, vocab), 0.0001)
      )

      for ((name, lm) <- lms) {
        println(name)
        println(lm.perplexity(test))
        println(lm.sampleMany(25).reverse)
      }
    }

  }

}

object CountTerms {

  import ml.wolfe.term.TermImplicits._

  trait LanguageModel {
    def order:Int
    val Words:DiscreteDom[String]
    val Histories = Seqs(Words,order - 1)
    val Ngrams = Seqs(Words,order)

    def counts(ngram:Ngrams.Term):DoubleTerm
    def normalizer(history:Histories.Term):DoubleTerm

    def prob(history:Histories.Term)(word:Words.Term) =
      counts(???) / normalizer(history)

  }




}


