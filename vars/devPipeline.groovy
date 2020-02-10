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
			COMMITTER_EMAIL="""${sh(returnStdout: true, script: "git show -s --format='%ae' $GIT_COMMIT").trim()}"""
		}

		stages {
			
			stage('Git Info') {
				steps {
					script {
					  sh "echo $COMMITTER_EMAIL"
					}
				}
			}

			stage('Build') {
				steps {
					container('maven') {
						sh "printenv"
					}
				}
			}
		}
	}
}
