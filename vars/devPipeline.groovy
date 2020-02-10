def call(body) {
	def pipelineParams = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = pipelineParams
	body()

	pipeline {
		agent {
			kubernetes {
				yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    pipeline: devPipeline
spec:
  containers:
  - name: maven
    image: maven:3.6-jdk-11-slim
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: "/root/.m2"
      name: "m2repo"
      readOnly: false
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command:
    - cat
    tty: true
  volumes:
  - name: "m2repo"
    hostPath:
      path: "/home/ec2-user/.m2"
"""
			}
		}

		environment {
			VERSION=readMavenPom().getVersion()
			DOCKER_CREDS=credentials('docker')
		}

		stages {

			stage('Build') {
				steps {
					container('maven') {
						script {
							def author = ""
							def changeSet = currentBuild.rawBuild.changeSets               
							for (int i = 0; i < changeSet.size(); i++) 
							{
							   def entries = changeSet[i].items;
							   for (int i = 0; i < changeSet.size(); i++) 
							            {
							                       def entries = changeSet[i].items;
							                       def entry = entries[0]
							                       author += "${entry.author}"
							            } 
							 }
							 print author;
						}
						sh "echo AUTHOR_NAME : ${author}"
					}
				}
			}
		}
	}
}
