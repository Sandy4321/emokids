package emokids

/**
 * This is a helper file for the homework. You do not need to modify anything in it
 * to complete the assignment. In fact, don't change anything in it. If you find
 * something that you think is a problem, contact the instructor to make a bug report.
 */

import nak.core.{LinearModel,LiblinearTrainer,LinearModelAdaptor}
import nak.data.{FeatureObservation,Featurizer}

/**
 * A trait for text classification functions. The return value is a pair consisting of
 * the best label for the input and the confidence assigned to that label by the
 * classifier.
 */
trait TextClassifier extends (String => (String, Double))

/**
 * A text classifier that assigns a single label to every text that is given to it.
 */
class MajorityClassBaseline(majorityClass: String, prob: Double)
  extends TextClassifier {
  def apply(content: String) = (majorityClass, prob)
}

/**
 * An object that implements a function for calculating the majority
 * class label given a sequence of labels, and its probability in that
 * sequence. E.g. for the labels "yes, yes, no, yes" it should
 * return (yes, .75).
 */
object MajorityClassBaseline {
  import nak.util.CollectionUtil._

  def apply(labels: Seq[String]) = {
    val (majorityLabel, majorityProb) =
      labels
        .counts
        .mapValues(_.toDouble / labels.length)
        .maxBy(_._2)

    new MajorityClassBaseline(majorityLabel, majorityProb)
  }
}

/**
 * An object that implements a function for splitting a string into a
 * sequence of tokens.
 */
object Tokenizer {
  def apply(text: String) = chalk.lang.eng.Twokenize(text)
  //def apply(text: String) = text.split("\\s+")
}


/**
 * A classifier that counts positive and negative terms in a text and picks a
 * label based on these counts. The label "neutral" is chosen when there are 
 * equal numbers of positive and negative tokens (or zero of both).
 */
class LexiconRatioClassifier extends TextClassifier {
  import Polarity._

  // Return the number of positive tokens in the token sequence.
  def numPositiveTokens(tokens: Seq[String]): Int = tokens.count(positive)

  // Return the number of negative tokens in the token sequence.
  def numNegativeTokens(tokens: Seq[String]): Int = tokens.count(negative)

  def apply(content: String) = {

    val tokens = Tokenizer(content)
    val numPositive = numPositiveTokens(tokens)
    val numNegative = numNegativeTokens(tokens)

    // Add a small count to each so we don't get divide-by-zero error
    val positiveScore = numPositive + .1
    val negativeScore = numNegative + .1

    // Let neutral be preferred if nothing is found, and go with neutral
    // if pos and neg are the same.
    val neutralScore =
      if (numPositive == numNegative) tokens.length
      else .2

    // Calculate a denominator so we can pretend we have probabilities.
    val denominator = positiveScore + negativeScore + neutralScore

    // Create pseudo-probabilities based on the counts
    val predictions =
      List(("positive", positiveScore / denominator),
        ("negative", negativeScore / denominator),
        ("neutral", neutralScore / denominator))

    // Sort and return the top label and its confidence
    predictions.sortBy(_._2).last
  }
}

/**
 * An adaptor class that allows a maxent model trained via OpenNLP Maxent to be
 * used in a way that conforms with the TextClassifier trait defined above.
 */
class NakClassifier(classifier: LinearModelAdaptor, featurizer: Featurizer[String,String])
  extends TextClassifier {
  val numOutcomes = classifier.getNumOutcomes
  val outcomes = (0 until numOutcomes).map(classifier.getOutcome(_))

  def apply(content: String) = {
    val prediction = classifier.evalRaw(featurizer(content)).toIndexedSeq
    val (prob, index) = prediction.zipWithIndex.maxBy(_._1)
    (outcomes(index), prob)
  }
}

/**
 * An adaptor that converts the tweets being classified into the event objects
 * needed to train an OpenNLP Maxent classifier, plus the ability to specify
 * the standard deviation (sigma) of the Gaussian prior, and the maximum number
 * of iterations. (Reasonable defaults for both are provided.)
 */
object NakClassifierTrainer {

  import nak.data.{Example,ExampleIndexer,BowFeaturizer}
  import nak.liblinear.LiblinearConfig

  def apply(featurizer: Featurizer[String,String], 
            labels: Seq[String], tweets: Seq[Tweet], 
            sigma: Double = 1.0, maxIterations: Int = 100) = {

    val rawExamples = for ((l,t) <- labels.zip(tweets)) yield 
      Example(l,t).map(tweet => featurizer(tweet.content))

    val indexer = new ExampleIndexer    
    val examples = rawExamples.map(indexer)
    val (lmapFixed,fmapFixed) = indexer.getMaps
        
    // Configure and train with liblinear.
    val config = new LiblinearConfig(cost=sigma)
    val classifier = LiblinearTrainer.train(examples, lmapFixed, fmapFixed, config)
    new NakClassifier(classifier, featurizer)
  }

}

object AttrVal {
  def apply(a: String, v: String) = FeatureObservation(a+":"+v)
}

/**
 * An implementation of a FeatureExtractor that extracts more information out
 * of a tweet than the DefaultFeatureExtractor defined in ClassifierUtil.scala.
 * This is the main part of the assignment.
 */
object ExtendedFeaturizer extends Featurizer[String,String] {

  // Import any classes and objects you need here. AttrVal is included already.
  import scala.util.matching.Regex
  import Polarity._
  import English.stopwords

  // Define any fields, including regular expressions and helper objects, here.
  // For example, you may want to include the lexicon ration classifier here (hint),
  // and a Porter stemmer, and whatever else you think might help.

  // End of sentence marker
  private val EOS = "[-*-EOS-*-]"

  val lexClassifier = new LexiconRatioClassifier
  val stemmer = new chalk.lang.eng.PorterStemmer
  val Threepeat = """\w+(.)\1\1+\w+""".r
  val AllCaps = """[^\w]*[A-Z][A-Z]+[^\w]*""".r

  // A class to allow an implicit conversion for easy regex handling.
  class Matcher(regex: Regex) {
    def fullMatch(input: String) = regex.pattern.matcher(input).matches
    def hasMatch(input: String) = regex.findAllIn(input).length > 1
  }

  // The implicit conversion of Regex to Matcher.
  implicit def regexToMatcher(regex: Regex) = new Matcher(regex)

  def apply(content: String) = {
    val tokens = Tokenizer(content).toSeq
    val contentTokens = tokens.filter(stopwords)
    val stems = tokens.map(stemmer(_))

    val unigrams =
      tokens
        .filterNot(stopwords)
        .map(token => AttrVal("unigram", token))
        .toSeq

    val polarityFeatures =
      tokens
        .map(_.replaceAll("#", ""))
        .flatMap { token =>
          if (positive(token)) Some(AttrVal("polarity", "POSITIVE"))
          else if (negative(token)) Some(AttrVal("polarity", "NEGATIVE"))
          else None
        }

    val bigrams = (Seq(EOS) ++ stems ++ Seq(EOS)).sliding(2).flatMap {
      case List(first, second) =>
        Some(AttrVal("bigram", first + "::" + second))
      case _ => None
    }

    val emphasis = tokens.flatMap { token =>
      if (Threepeat.hasMatch(token)) Some("[-THREEPEAT-]")
      else if (AllCaps.fullMatch(token)) Some("[-ALLCAPS-]")
      else if (token.endsWith("!")) Some("[-EXCLAMATION-]")
      else None
    }
    
    val emphasisFeatures = emphasis.map(AttrVal("emphasis",_))

    //val trigrams = (Seq(EOS, EOS) ++ stems ++ Seq(EOS, EOS)).sliding(3).flatMap {
    //  case List(first, second, third) =>
    //    Some(AttrVal("trigram", first + "::" + second + "::" + third))
    //  case _ => None
    //}

    val subjectiveTokens =
      contentTokens.flatMap { token =>
        if (positive(token)) Some("[-POSITIVE-]")
        else if (negative(token)) Some("[-NEGATIVE-]")
        else Some("[-NEUTRAL-]")
      }

    val bigramPolarity = (Seq(EOS) ++ subjectiveTokens ++ Seq(EOS)).sliding(2).flatMap {
      case List(first, second) =>
        Some(AttrVal("bigramPolarity", first + "::" + second))
      case _ => None
    }.toSeq

    (unigrams
      ++ Seq(AttrVal("lexratio", lexClassifier(content)._1))
      ++ bigrams
      ++ bigramPolarity
      ++ emphasisFeatures
     //++ trigrams
      ++ polarityFeatures)
  }
}
