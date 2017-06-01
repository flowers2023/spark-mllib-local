package org.apache.spark.ml.utils

import scala.collection.mutable.ListBuffer

/**
  * Created by shuangfu on 17-5-17.
  * Author : DRUNK
  * email :len1988.zhang@gmail.com
  */
object WordUtil extends Serializable {

  def getCombine(words: Seq[String], minLengthPersent: Double): List[(String, String)] = {
    val len = words.length
    val startIndex = if (len < 3) 1 else Math.floor(len * minLengthPersent).toInt
    var combList = new ListBuffer[(String, String)]
    if (len.equals(1)) {
      List((words(0), ""))
    } else if (len.equals(2)) {
      List((words(0), words(1)),
        (words(1), words(0)),
        (words(0) + " " + words(1), "")
      )
    } else {
      for (i <- startIndex until (len)) {
        getCombineByNum(words, i, combList)
      }
      combList += Tuple2(words.mkString(" "), "")
      combList.toList
    }
  }

  def getCombineByNum(data: Seq[String], num: Int, combList: ListBuffer[(String, String)]) = {
    require((null != data && data.length > 0 && num > 0 && num <= data.length),
      s"""传入的数据有误，无法进行排列组合.${data.mkString(",")}""")
    var temArr = new Array[String](num)
    genComb(data, num, 0, temArr, 0, combList)
  }

  def getScore(antecedent: Array[String], q_words: Array[String], q_consequent: Array[String], q_vector: Array[Double],
               f_words: Array[String], f_consequent: Array[String], f_vector: Array[Double]): Double = {
    val score = if (antecedent.length == 0 || f_words.length == 0 || q_words.length == 0) {
      0D
    } else if (q_consequent.length == 0) {
      antecedent.length.toDouble / f_words.length.toDouble
    } else if (antecedent.length == f_words.length) {
      antecedent.length.toDouble / q_words.length.toDouble
    } else {
      val common = antecedent.length.toDouble / q_words.length.toDouble
      val left = 1 - common
      val wordCount = antecedent.length.toDouble / f_words.length.toDouble
      val similarity = VectorUtil.getCosine(q_vector, f_vector)
      if (similarity == 0) {
        common + wordCount * left
      } else {
        common + (wordCount * 0.3 + similarity * 0.7) * left
      }
    }
    score
  }

  private def genComb(data: Seq[String], num: Int, begin: Int, temArr: Array[String], index: Int, combList: ListBuffer[(String, String)]): Unit = {
    if (num == 0) {
      combList += Tuple2(temArr.mkString(" "), data.diff(temArr).mkString(" "))
      return;
    } else {
      for (i <- begin until data.length) {
        temArr(index) = data(i)
        genComb(data, num - 1, i + 1, temArr, index + 1, combList)
      }
    }
  }

}
