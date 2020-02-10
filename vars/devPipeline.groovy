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
						sh "echo GIT_COMMIT : ${env.GIT_COMMIT}"
						sh "echo GIT_COMMITTER_NAME : ${env.GIT_COMMITTER_NAME}"
						sh "echo GIT_COMMITTER_EMAIL : ${env.GIT_COMMITTER_EMAIL}"
						sh "echo GIT_COMMITTER_DATE : ${env.GIT_COMMITTER_DATE}"
						sh "echo GIT_URL : ${env.GIT_URL}"
						sh "echo GIT_BRANCH : ${env.GIT_BRANCH}"
						sh "echo GIT_LOCAL_BRANCH : ${env.GIT_LOCAL_BRANCH}"
					}
				}
			}
		}
	}
}
