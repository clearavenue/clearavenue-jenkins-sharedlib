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
			DOCKER_CREDS=credentials('docker')
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
								sh "mvn -B -e -T 1C org.jacoco:jacoco-maven-plugin:0.8.6:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.6:report"
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

					stage('SpotBugs') {
						steps {
							container('maven') {
								sh "mvn -B -e -T 1C com.github.spotbugs:spotbugs-maven-plugin:4.2.2:check -Dspotbugs.effort=Max -Dspotbugs.threshold=Low"
							}
						}
						post {
							always {
								recordIssues(enabledForFailure: true, tool: spotBugs())
							}
						}
					}

					stage('PMD') {
						steps {
							container('maven') {
								sh "mvn -B -e org.apache.maven.plugins:maven-jxr-plugin:3.0.0:jxr org.apache.maven.plugins:maven-pmd-plugin:3.14.0:pmd"
							}
						}
						post {
							always {
								recordIssues(enabledForFailure: true, tool: pmdParser(pattern: 'target/pmd.xml'))
							}
						}
					}

					stage('Vulnerabilities') {
						steps {
							container('maven') {
								sh "mvn -B -e -T 1C org.owasp:dependency-check-maven:6.1.5:aggregate -Dformat=xml -DfailBuildOnCVSS=7"
							}
						}
						post {
							always {
								dependencyCheckPublisher(failedTotalCritical : 100, unstableTotalCritical : 100)
							}
						}
					}
				}
			}

			stage('Push Docker') {
				steps {
					container('maven') {
						script {
							VERSION = ((env.GIT_BRANCH != 'master') ? "$POM_VERSION.$BUILD_NUMBER-$BRANCH" : "$POM_VERSION.$BUILD_NUMBER")
							sh "mvn -B -e -T 1C com.google.cloud.tools:jib-maven-plugin:2.0.0:build -Dimage=${pipelineParams.docker_user}/${pipelineParams.service_name}:${VERSION} -DskipTests -Djib.to.auth.username=$DOCKER_CREDS_USR -Djib.to.auth.password=$DOCKER_CREDS_PSW -Djib.allowInsecureRegistries=true"
						}
					}
				}
			}

			stage('Deploy') {
				steps {
					container('kubectl') {
						script {
							withKubeConfig([credentialsId: 'kube-admin', serverUrl: 'https://10.43.0.1']) {
								
								VERSION = ((env.GIT_BRANCH != 'master') ? "$POM_VERSION.$BUILD_NUMBER-$BRANCH" : "$POM_VERSION.$BUILD_NUMBER")

								sh "sed -i 's|APP_NAME|${pipelineParams.app_name}|g' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|SERVICE_NAME|${pipelineParams.service_name}|g' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|DOCKER_USER|${pipelineParams.docker_user}|' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|SERVICE_PORT|${pipelineParams.service_port}|g' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|LIVENESS_URL|${pipelineParams.liveness_url}|g' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|READINESS_URL|${pipelineParams.readiness_url}|g' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|HOST_NAME|${pipelineParams.host_name}|g' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|:latest|:${VERSION}|' ${pipelineParams.deploymentFile}"
								sh "sed -i 's|BRANCH_NAME|${BRANCH}|g' ${pipelineParams.deploymentFile}"
								
								sh "cat ${pipelineParams.deploymentFile}"
								sh "kubectl version"
								sh "kubectl apply -f ${pipelineParams.deploymentFile}"
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
