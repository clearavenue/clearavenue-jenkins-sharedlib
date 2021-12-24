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
                        ENV = "prod"
                        IMAGE_NAME="dummy"
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

                                        stage('SpotBugs') {
                                                steps {
                                                        container('maven') {
                                                                sh "mvn -B -e -T 1C com.github.spotbugs:spotbugs-maven-plugin:4.5.0.0:check -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.failOnError=false"
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
                                                                sh "mvn -B -e org.apache.maven.plugins:maven-jxr-plugin:3.1.1:jxr org.apache.maven.plugins:maven-pmd-plugin:3.14.0:pmd"
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
                                                                sh "mvn -B -e -T 1C org.owasp:dependency-check-maven:6.5.1:aggregate -Dformat=xml -DfailBuildOnCVSS=10"
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
                                                   APP_NAME=pipelineParams.app_name
                                                   BRANCH_NAME="-"+BRANCH

                                                   if (BRANCH_NAME == '-main' || BRANCH_NAME == '-master') {
                                                      IMAGE_NAME = APP_NAME
                                                   }  else {
                                                      IMAGE_NAME = APP_NAME+BRANCH_NAME
                                                   }

                                                   sh "mvn -B -e -T 1C com.google.cloud.tools:jib-maven-plugin:3.1.4:build -Dimage=${DOCKER_CREDS_USR}/${IMAGE_NAME}:${POM_VERSION} -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.allowInsecureRegistries=true"
                                                }
                                        }
                                }
                        }

                        stage('Deploy') {
                                steps {
                                        container('kubectl') {
                                                script {
                                                        withKubeConfig([credentialsId: 'jenkins-serviceaccount', serverUrl: 'https://2176A80F2DE9138595ADC878309B7CEC.gr7.us-east-1.eks.amazonaws.com']) {
                                                           APP_NAME=pipelineParams.app_name
                                                           BRANCH_NAME="-"+BRANCH

                                                           if (BRANCH_NAME == '-main' || BRANCH_NAME == '-master') {
                                                              IMAGE_NAME = APP_NAME
                                                           }  else {
                                                              IMAGE_NAME = APP_NAME+BRANCH_NAME
                                                           }
                                                           
                                                           sh "echo [$APP_NAME] [$BRANCH_NAME] [$IMAGE_NAME] [$POM_VERSION]"
                                                           sh "apk add curl"
                                                           sh "curl -sL https://git.io/getLatestIstio | sh -"
                                                           sh "cp istio-\$(curl -sL https://github.com/istio/istio/releases | grep -o 'releases/[0-9]*.[0-9]*.[0-9]*/' | sort -V | tail -1 | awk -F'/' '{ print \$2}')/bin/istioctl /usr/local/bin"

                                                           sh "curl https://raw.githubusercontent.com/clearavenue/clearavenue-jenkins-sharedlib/main/deploy.yaml > deploy.yaml"

                                                           sh "sed -i 's|APP_NAME|${APP_NAME}|g' deploy.yaml"                                                           
                                                           
                                                           if (BRANCH_NAME == '-main' || BRANCH_NAME == '-master') {
                                                              sh "sed -i 's|-BRANCH||g' deploy.yaml"   
                                                           } else {
                                                              sh "sed -i 's|-BRANCH|${BRANCH_NAME}|g' deploy.yaml"
                                                           }
                                                            
                                                           if (ENV == 'prod') {
                                                              sh "sed -i 's|-ENV||g' deploy.yaml"
                                                           }

                                                           sh "sed -i 's|ENV|${ENV}|g' deploy.yaml"
                                                           sh "sed -i 's|VERSION|${POM_VERSION}|g' deploy.yaml"
                                                           sh "sed -i 's|DOCKER_USER|${DOCKER_CREDS_USR}|g' deploy.yaml"

                                                           sh "cat deploy.yaml"

                                                           sh "istioctl kube-inject -f deploy.yaml --output deploy-injected.yaml"
                                                           sh "kubectl apply -f deploy-injected.yaml"
                                                        }
                                                }
                                        }
                                }
                        }

                        stage('Delete Deployment') {
                                steps { 
                                    script { 
                                          def proceed = true 
                                          try { 
                                              timeout(time: 100, unit: 'SECONDS') { 
                                                     input('Delete Deploys?') 
                                               } 
                                          } 
                                          catch (err) { 
                                               proceed = false 
                                          } 
                                     }
                                }
                        }

                        stage('Delete prod deployment') {
                                steps {
                                        container('kubectl') {
                                                script {
                                                        withKubeConfig([credentialsId: 'jenkins-serviceaccount', serverUrl: 'https://2176A80F2DE9138595ADC878309B7CEC.gr7.us-east-1.eks.amazonaws.com']) {
                                                           sh "kubectl delete -f deploy.yaml"
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
