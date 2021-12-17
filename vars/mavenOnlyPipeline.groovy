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
                        
                        stage('SecurityChecks') {
                                parallel {
                                        stage('Checkstyle code') {
                                                steps {
                                                        container('maven') {
                                                                sh "mvn -B -e -T 1C org.apache.maven.plugins:maven-checkstyle-plugin:3.1.2:checkstyle -Dcheckstyle.config.location=google_checks.xml"
                                                        }
                                                }
                                                post {
                                                        always {
                                                                recordIssues(enabledForFailure: false, tool: checkStyle(pattern: 'target/checkstyle-result.xml'))
                                                        }
                                                }
                                        }

                                        stage('CodeCoverage') {
                                                steps {
                                                        container('maven') {
                                                                sh "mvn -B -e -T 1C org.jacoco:jacoco-maven-plugin:0.8.7:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.7:report"
                                                                jacoco(execPattern: 'target/jacoco.exec', classPattern: 'target/classes', sourcePattern: 'src/main/java', exclusionPattern: 'src/test*', changeBuildStatus: true,
                                                                minimumInstructionCoverage : '30', maximumInstructionCoverage : '31',
                                                                minimumBranchCoverage : '30', maximumBranchCoverage : '31',
                                                                minimumComplexityCoverage : '30', maximumComplexityCoverage : '31',
                                                                minimumLineCoverage : '30', maximumLineCoverage : '31',
                                                                minimumMethodCoverage : '30', maximumMethodCoverage : '31',
                                                                minimumClassCoverage : '30', maximumClassCoverage : '31')
                                                        }
                                                }
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
