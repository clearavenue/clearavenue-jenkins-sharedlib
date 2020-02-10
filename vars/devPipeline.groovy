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
						def scmVars = checkout(scm)
						sh "echo scmVars.GIT_COMMIT"
						sh "echo ${scmVars.GIT_COMMIT}"
				  
						env.GIT_COMMIT = scmVars.GIT_COMMIT
						sh "echo env.GIT_COMMIT"
						sh "echo ${env.GIT_COMMIT}"
					}
				}
			}
		}
	}
}
