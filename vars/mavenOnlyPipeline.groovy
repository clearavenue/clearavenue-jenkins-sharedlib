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
    pipeline: mavenDevsecopsPipeline
spec:
  containers:
  - name: maven
    image: maven:3.6-jdk-11-slim
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:v1.19.9
    command:
    - cat
    tty: true
  - name: jnlp
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi
"""
			}
		}

		environment {
			POM_VERSION=readMavenPom().getVersion()
			BRANCH = env.GIT_BRANCH.toLowerCase()
		}

		stages {

			stage('Build') {
				steps {
					container('maven') {
						sh "mvn -B -e -T 1C clean compile -DskipTests"
					}
				}
			}

			stage('JUnit') {
				steps {
					container('maven') {
						sh "mvn -B -e -T 1C test"
						junit 'target/surefire-reports/**/*.xml'
					}
				}
			}

		}

		post {
			always {
				emailext attachLog: true, subject: '$DEFAULT_SUBJECT', body: '$DEFAULT_CONTENT', recipientProviders: [
					[$class: 'CulpritsRecipientProvider'],
					[$class: 'DevelopersRecipientProvider'],
					[$class: 'RequesterRecipientProvider']
				]
			}
		}
	}
}
