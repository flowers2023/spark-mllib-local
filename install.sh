#!/bin/bash

#########################################################################
# Function: 
# Author: DRUNK
# mail: shuangfu.zhang@xiaoi.com
# Created Time: 2017年02月13日 星期一 18时44分28秒
#########################################################################
mvn clean package && \
mvn install:install-file \
  -Dfile=target/spark-mllib-local_2.11-2.2.0-SNAPSHOT.jar \
  -DgroupId=org.apache.spark.ml \
  -DartifactId=spark-mllib-local_2.11 \
  -Dversion=2.2.0 \
  -Dpackaging=jar
#mvn deploy:deploy-file \
#  -DgroupId=com.drunk2013.spark \
#  -DartifactId=spark-mllib-local_2.11 \
#  -Dversion=2.1.0 \
#  -Dpackaging=jar \
#  -Dfile=target/spark-mllib2-local_2.11-2.2.0-SNAPSHOT.jar \
#  -Durl=http://122.226.240.154:8081/nexus/content/repositories/thirdparty/ \
#  -DrepositoryId=xiaoi
