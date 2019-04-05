package ai.newmap.nluLayer

import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import scala.io.Source
import java.io.PrintWriter
import java.nio.file.{Paths, Files}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileOutputStream
import org.apache.commons.io.IOUtils
import scala.collection.JavaConversions._
import scala.collection.mutable.ListMap

import ai.newmap.environment.envConstant

object argParser {
	val BUCKET_NAME = envConstant.BUCKET_NAME
	val AWS_ACCESS_KEY = envConstant.AWS_ACCESS_KEY
	val AWS_SECRET_KEY = envConstant.AWS_SECRET_KEY

	val S3_ModelFileName_Prefix = "Model/"
	val S3_CacheFileName_Prefix = "CACHE/"

	val CreateEnvIndMap:ListMap[String, String] = ListMap.empty[String,String]
	val CreateDsIndMap:ListMap[String, String] = ListMap.empty[String,String]

	val LogInIndMap:ListMap[String, String] = ListMap.empty[String, String]

	val CheckOutIndMap:ListMap[String, String] = ListMap.empty[String, String]

	val CommentEnvIndMap:ListMap[String, String] = ListMap.empty[String, String]

	def parseCommentEnvArg(msg: String, nluCacheFileName: String): String = {
		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

		loadCommentEnvModel

		val cont = msg.toLowerCase.split("\\s+").toList
		var arg1 = ""	// env name
		var gotArg1 = false
		var arg2 = ""	// password
		var gotArg2 = false
		var arg3 = ""   // comment String
		var gotArg3 = false
/*
		for(i <- 0 to cont.size-1 if (!gotArg1 || !gotArg2 || !gotArg3)){
			val tok = cont(i)
			if(CommentEnvIndMap.contains(tok)){
				val tmp = CommentEnvIndMap(tok)
				if(!gotArg1 && tmp.equals("1") && i < cont.size-1){
					arg1 = cont(i+1)
					gotArg1 = true
				}
				if(!gotArg2 && tmp.equals("2") && i < cont.size-1){
					arg2 = cont(i+1)
					gotArg2 = true
				}
				if(!gotArg3 && tmp.equals("3") && i < cont.size-1){
					var index: Int = i+1
					while(index < cont.size && cont(index) != "#"){
						arg3 += cont(index)+" "
						index += 1
					}
					gotArg3 = true
				}
			}
		}
*/		
		
		//println(CommentEnvIndMap)
		for((k,v) <- CommentEnvIndMap if(!gotArg1 || !gotArg2 || gotArg3)) {
			if(cont.contains(k)){
				val index = cont.indexOf(k)
				val tmp = CommentEnvIndMap(k)
				if(!gotArg1 && tmp.equals("1") && index < cont.size-1) {
					arg1 = cont(index+1)
					gotArg1 = true
				}
				if(!gotArg2 && tmp.equals("2") && index < cont.size-1) {
					arg2 = cont(index+1)
					gotArg2 = true
				}
				if(!gotArg2 && tmp.equals("3") && index < cont.size-1) {
					var i: Int = index + 1
					while(i < cont.size && cont(i) != "#"){
						arg3 += cont(i)+" "
						i += 1
					}
					gotArg3 = true
				}
			} 
		}

		if(!gotArg1){
			nluInterpreter.writeToCache("comment on env ", nluCacheFileName)
			return "*I understand u want to comment, but missing env name, Please tell me a env name*"
		}else if(!gotArg3){
			nluInterpreter.writeToCache("comment on  env "+arg1+" with ", nluCacheFileName)
			return "I understand u want to comment, but missing comment string, Please tell me what u want to comment*"
		}else if(!gotArg2){
			nluInterpreter.writeToCache("comment on env "+arg1+" with "+arg3+" # password ", nluCacheFileName)
			return "*Please tell me the password for this env*"
		}else{
			amazonS3Client.deleteObject(BUCKET_NAME, S3_CacheFileName_Prefix+nluCacheFileName)
			return "*I understand u want to comment on "+arg1+" with comment: "+arg3+". \nInterpret finished."
		}

	}

	def parseLogInArg(msg:String, nluCacheFileName: String): String = {
		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

		loadLogInIndModel

		val cont = msg.toLowerCase.split("\\s+").toList
		var arg1 = ""	// env name
		var gotArg1 = false
		var arg2 = "" 	// password
		var gotArg2 = false
/*
		for(i <- 0 to cont.size-1 if (!gotArg1 || !gotArg2)) {
			val tok = cont(i)
			if(LogInIndMap.contains(tok) && i < cont.size-1){
				val tmp = LogInIndMap(tok)
				if(!gotArg1 && tmp.equals("1") && i < cont.size-1){
					arg1 = cont(i+1)
					gotArg1 = true
				}
				if(!gotArg2 && tmp.equals("2") && i < cont.size-1){
					arg2 = cont(i+1)
					gotArg2 = true
				}
			}
		}
*/
		for((k,v) <- LogInIndMap if(!gotArg1 || !gotArg2)) {
			if(cont.contains(k)){
				val index = cont.indexOf(k)
				val tmp = LogInIndMap(k)
				if(!gotArg1 && tmp.equals("1") && index < cont.size-1) {
					arg1 = cont(index+1)
					gotArg1 = true
				}
				if(!gotArg2 && tmp.equals("2") && index < cont.size-1) {
					arg2 = cont(index+1)
					gotArg2 = true
				}
			} 
		}

		if(!gotArg1){
			nluInterpreter.writeToCache("access ", nluCacheFileName)
			return "*missing env name, Please tell me a env name*"
		}else if(!gotArg2){
			nluInterpreter.writeToCache("access "+arg1+" with password ", nluCacheFileName)
			return "*missing password, Please tell me the password for this env.*"
		}else{
			amazonS3Client.deleteObject(BUCKET_NAME, S3_CacheFileName_Prefix+nluCacheFileName)
			return "*got env name: "+arg1+". got password "+arg2+". * \nInterpret finished."
		}	
	}


	def parseCreateEnvArg(msg: String, nluCacheFileName: String): String = {	// needs two args

		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

  		loadCreateEnvIndModel

		val cont = msg.toLowerCase.split("\\s+").toList
		var arg1 = ""	// env name
		var gotArg1 = false
		var arg2 = ""	// password
		var gotArg2 = false
/*
		for(i <- 0 to cont.size-1 if (!gotArg1 || !gotArg2)){
			val tok = cont(i)
			if(CreateEnvIndMap.contains(tok)){
				val tmp = CreateEnvIndMap(tok)
				if(!gotArg1 && tmp.equals("1") && i < cont.size-1){
					arg1 = cont(i+1)
					gotArg1 = true
				}
				if(!gotArg2 && tmp.equals("2") && i < cont.size-1){
					arg2 = cont(i+1)
					gotArg2 = true
				}
			}
		}
*/

		for((k,v) <- CreateEnvIndMap if(!gotArg1 || !gotArg2)) {
			if(cont.contains(k)){
				val index = cont.indexOf(k)
				val tmp = CreateEnvIndMap(k)
				if(!gotArg1 && tmp.equals("1") && index < cont.size-1) {
					arg1 = cont(index+1)
					gotArg1 = true
				}
				if(!gotArg2 && tmp.equals("2") && index < cont.size-1) {
					arg2 = cont(index+1)
					gotArg2 = true
				}
			} 
		}

		var ret = ""
		if(!gotArg1){
			nluInterpreter.writeToCache(msg + " called ", nluCacheFileName)
			ret += "*missing env name. Please tell me a env name*"
			return ret
		}else{
			ret += "*got env name: "+arg1+". "
		}
		if(!gotArg2){
			nluInterpreter.writeToCache(msg + " password ", nluCacheFileName)
			ret += "missing password. Please tell me a password*"
			return ret
		}else{
			ret += "got password: "+arg2+". "
		}
		println("*** Finish interpret a create env message! ***")
		amazonS3Client.deleteObject(BUCKET_NAME, S3_CacheFileName_Prefix+nluCacheFileName)
		ret+"\nInterpret finished. *"
	}

	def parseCheckOutArg(msg: String, nluCacheFileName: String): String = {
		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

		loadCheckOutIndModel

		val cont = msg.toLowerCase.split("\\s+").toList
		var arg = ""
		var gotArg = false

		for((k,v) <- CheckOutIndMap if !gotArg){
			if(cont.contains(k) && cont.indexOf(k) < cont.size-1){
				arg = cont(cont.indexOf(k)+1)
				gotArg = true
			}
		}

		if(!gotArg){
			nluInterpreter.writeToCache("show me the content with commit num ", nluCacheFileName)
			return "*missing the commit number you want to check, Please tell me the commit number \n(you can type 'what's in the log' to check all commit numbers)*"
		}else{
			amazonS3Client.deleteObject(BUCKET_NAME, S3_CacheFileName_Prefix+nluCacheFileName)
			return "*Got commit number "+arg+".* \nInterpret finished."
		}

	}

	def loadCreateEnvIndModel() = {
  		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

  		val CreateEnvIndFileName = "create_env_model.txt"
  		val CreateEnvIndObj = amazonS3Client.getObject(BUCKET_NAME, S3_ModelFileName_Prefix+CreateEnvIndFileName)
  		val CreateEnvIndReader = new BufferedReader(new InputStreamReader(CreateEnvIndObj.getObjectContent()))
  		var CreateEnvIndLine = CreateEnvIndReader.readLine
  		while(CreateEnvIndLine != null){
  			val cont = CreateEnvIndLine.split(",")
  			CreateEnvIndMap += (cont(0) -> cont(1))
  			CreateEnvIndLine = CreateEnvIndReader.readLine
  		}

  		println("*** create env map ***")
  		CreateEnvIndMap.forEach{case (key, value) => println (key + "-->" + value)}

  	}

  	def loadCreateDsIndModel() = {
  		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

  		val CreateDsIndFileName = "create_ds_model.txt"
  		val CreateDsIndObj = amazonS3Client.getObject(BUCKET_NAME, S3_ModelFileName_Prefix+CreateDsIndFileName)
  		val CreateDsIndReader = new BufferedReader(new InputStreamReader(CreateDsIndObj.getObjectContent()))
  		var CreateDsIndLine = CreateDsIndReader.readLine
  		while(CreateDsIndLine != null){
  			val cont = CreateDsIndLine.split(",")
  			CreateDsIndMap += (cont(0) -> cont(1))
  			CreateDsIndLine = CreateDsIndReader.readLine
  		}

  		println("*** create ds map ***")
  		CreateDsIndMap.forEach{case (key, value) => println (key + "-->" + value)}
  	}

  	def loadLogInIndModel() = {
 		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

  		val logInIndFileName = "log_in_model.txt"
  		val logInIndObj = amazonS3Client.getObject(BUCKET_NAME, S3_ModelFileName_Prefix+logInIndFileName)
  		val logInIndReader = new BufferedReader(new InputStreamReader(logInIndObj.getObjectContent()))
  		var logInIndLine = logInIndReader.readLine
  		while(logInIndLine != null){
  			val cont = logInIndLine.split(",")
  			LogInIndMap += (cont(0) -> cont(1))
  			logInIndLine = logInIndReader.readLine
  		}

  		println("*** log in map ***")
  		LogInIndMap.forEach{case (key, value) => println (key + "-->" + value)}
 	}

 	def loadCommentEnvModel() = {
 		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

  		val commentEnvIndFileName = "comment_env_model.txt"
  		val commentEnvIndObj = amazonS3Client.getObject(BUCKET_NAME, S3_ModelFileName_Prefix+commentEnvIndFileName)
  		val commentEnvIndReader = new BufferedReader(new InputStreamReader(commentEnvIndObj.getObjectContent()))
  		var commentEnvIndLine = commentEnvIndReader.readLine
  		while(commentEnvIndLine != null){
  			val cont = commentEnvIndLine.split(",")
  			CommentEnvIndMap += (cont(0) -> cont(1))
  			commentEnvIndLine = commentEnvIndReader.readLine
  		}

  		println("*** commentEnv map ***")
  		CommentEnvIndMap.forEach{case (key, value) => println (key + "-->" + value)}
 	}

 	def loadCheckOutIndModel() = {
 		val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
  		val amazonS3Client = new AmazonS3Client(awsCredentials)

  		val checkOutIndFileName = "check_out_model.txt"
  		val checkOutIndObj = amazonS3Client.getObject(BUCKET_NAME, S3_ModelFileName_Prefix+checkOutIndFileName)
  		val checkOutIndReader = new BufferedReader(new InputStreamReader(checkOutIndObj.getObjectContent()))
  		var checkOutIndLine = checkOutIndReader.readLine
  		while(checkOutIndLine != null){
  			val cont = checkOutIndLine.split(",")
  			CheckOutIndMap += (cont(0) -> cont(1))
  			checkOutIndLine = checkOutIndReader.readLine
  		}

  		println("*** check out map ***")
  		CheckOutIndMap.forEach{case (key, value) => println (key + "-->" + value)}
 	}

}
