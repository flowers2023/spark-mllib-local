package org.apache.spark.ml.utils

import org.apache.spark.ml.linalg.{BLAS2, DenseVector, Vectors}

/**
  * Created by shuangfu on 17-5-5.
  * Author : DRUNK
  * email :len1988.zhang@gmail.com
  */
object VectorUtil {

  /**
    * 获取欧式距离
    *
    * @param vectorTarget
    * @param vectorOrg
    * @return
    */
  def getDistance(vectorTarget: Array[Double], vectorOrg: Array[Double]): Double = {
    val v1 = Vectors.dense(vectorTarget)
    val v2 = Vectors.dense(vectorOrg)
    Vectors.sqdist(v1, v2)
  }

  /**
    * 获取欧式距离
    *
    * @param vector
    * @return
    */
  def getDistance(vector: Array[Double]): Double = {
    val v1 = Vectors.dense(vector)
    val v2 = Vectors.zeros(vector.length)
    Vectors.sqdist(v1, v2)
  }

  /**
    * 获取向量的余弦值
    *
    * @param vectorTarget
    * @param vectorOrg
    * @return
    */
  def getCosine(vectorTarget: Array[Double], vectorOrg: Array[Double]): Double = {
    if (
      vectorOrg.isEmpty || vectorTarget.isEmpty ||
        vectorOrg.max == vectorOrg.min ||
        vectorTarget.max == vectorTarget.min ||
        vectorOrg.length != vectorTarget.length) 0D
    else {
      val a = vectorOrg.zip(vectorTarget).map(x => x._1 * x._2).reduce(_ + _)
      val b = math.sqrt(vectorOrg.map(x => math.pow(x, 2)).reduce(_ + _)) *
        math.sqrt(vectorTarget.map(x => math.pow(x, 2)).reduce(_ + _))
      if (b == 0) 0
      else a / b
    }
  }

  /**
    * 获取向量的余弦值
    *
    * @param vectorTarget
    * @return
    */
  def getCosine(vectorTarget: Array[Double]): Double = {
    val vectorOrg = Array[Double](vectorTarget.length)
    val a = vectorOrg.zip(vectorTarget).map(x => x._1 * x._2).reduce(_ + _)
    val b = math.sqrt(vectorOrg.map(x => math.pow(x, 2)).reduce(_ + _)) *
      math.sqrt(vectorTarget.map(x => math.pow(x, 2)).reduce(_ + _))
    a / b
  }

  def words2vec(sentence: Array[Array[Double]], size: Int): Array[Double] = {
    val word2Vec = if (sentence.isEmpty) {
      Vectors.sparse(size, Array.empty[Int], Array.empty[Double])
    } else {
      val sum = Vectors.zeros(size)
      sentence.foreach { word =>
        BLAS2.axpy(1.0D, Vectors.dense(word), sum)
      }
      BLAS2.scal(1.0 / sentence.size, sum)
      sum
    }
    word2Vec.toArray
  }

  //val word2Vec = udf { sentence: Seq[String] =>
  //  if (sentence.isEmpty) {
  //    Vectors.sparse(d, Array.empty[Int], Array.empty[Double])
  //  } else {
  //    val sum = Vectors.zeros(d)
  //    sentence.foreach { word =>
  //      bVectors.value.get(word).foreach { v =>
  //        BLAS.axpy(1.0, v, sum)
  //      }
  //    }
  //    BLAS.scal(1.0 / sentence.size, sum)
  //    sum
  //  }


}
