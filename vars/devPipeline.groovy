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
						sh "echo GIT_COMMIT %GIT_COMMIT%"
						sh "echo GIT_BRANCH %GIT_BRANCH%"
						sh "echo GIT_LOCAL_BRANCH %GIT_LOCAL_BRANCH%"
						sh "echo GIT_PREVIOUS_COMMIT %GIT_PREVIOUS_COMMIT%"
						sh "echo GIT_PREVIOUS_SUCCESSFUL_COMMIT %GIT_PREVIOUS_SUCCESSFUL_COMMIT%"
						sh "echo GIT_URL %GIT_URL%"
						sh "echo GIT_URL_N - %GIT_URL_N%"
						sh "echo GIT_AUTHOR_NAME %GIT_AUTHOR_NAME%"
						sh "echo GIT_COMMITTER_EMAIL %GIT_COMMITTER_EMAIL%"
					}
				}
			}
		}
	}
}
