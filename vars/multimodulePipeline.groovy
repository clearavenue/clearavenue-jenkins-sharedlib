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
    pipeline: jhipsterWebAppPipeline
spec:
  containers:
  - name: jhipster
    image: jhipster/jhipster:v7.6.0
    securityContext:
      privileged: true
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi
  - name: jnlp
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi
  - name: maven
    image: maven:3.6-jdk-11-slim
    securityContext:
      privileged: true
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi
  - name: git
    image: bitnami/git:latest
    command:
    - cat
    tty: true
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
            BUILD_NUM=currentBuild.getNumber()
            DOCKER_CREDS=credentials('docker')
            GIT_CREDS=credentials('bill.hunt-github')
            BRANCH = env.GIT_BRANCH.toLowerCase()
            APP_BRANCH="dummy"
        }

        stages {
            stage('Build') {
                steps {
                    container('jhipster') {
                        script {
                             BUILD_PROFILE=pipelineParams.buildProfile
                             sh "chmod +x mvnw"
                             sh "./mvnw -B -e -T 1C clean package ${BUILD_PROFILE} -DskipTests"
                        }
                    }
                }
            }  // end build

            stage('JUnit') {
                steps {
                    container('jhipster') {
                        sh "./mvnw -B -e -T 1C test"
                        //junit 'target/surefire-reports/**/*.xml'
                    }
                }
            }  // end junit

            stage('SecurityChecks') {
                parallel {
                    stage('Checkstyle code') {
                        steps {
                            container('jhipster') {
                                sh "./mvnw -B -e -T 1C org.apache.maven.plugins:maven-checkstyle-plugin:3.1.2:checkstyle -Dcheckstyle.config.location=google_checks.xml"
                            }
                        }
                        post {
                            always {
                                recordIssues(enabledForFailure: false, tool: checkStyle(pattern: 'target/checkstyle-result.xml'))
                            }
                        }
                    }

                    //stage('CodeCoverage') {
                    //    steps {
                    //        container('jhipster') {
                    //            sh "./mvnw -B -e -T 1C org.jacoco:jacoco-maven-plugin:0.8.7:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.7:report"
                    //            jacoco(execPattern: 'target/jacoco.exec', classPattern: 'target/classes', sourcePattern: 'src/main/java', exclusionPattern: 'src/test*', changeBuildStatus: false,
                    //                   minimumInstructionCoverage : '30', maximumInstructionCoverage : '31',
                    //                   minimumBranchCoverage : '30', maximumBranchCoverage : '31',
                    //                   minimumComplexityCoverage : '30', maximumComplexityCoverage : '31',
                    //                   minimumLineCoverage : '30', maximumLineCoverage : '31',
                    //                   minimumMethodCoverage : '30', maximumMethodCoverage : '31',
                    //                   minimumClassCoverage : '30', maximumClassCoverage : '31')
                    //        }
                    //    }
                    //}  // end codecoverage

                    //stage('SpotBugs') {
                    //    steps {
                    //       container('jhipster') {
                    //            sh "./mvnw -B -e -T 1C com.github.spotbugs:spotbugs-maven-plugin:4.5.3.0:check -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.failOnError=false"
                    //        }
                    //    }
                    //    post {
                    //        always {
                    //            recordIssues(enabledForFailure: true, tool: spotBugs())
                    //        }
                    //    }
                    //} // end spotbugs

                    stage('PMD') {
                        steps {
                            container('jhipster') {
                                sh "./mvnw -B -e org.apache.maven.plugins:maven-jxr-plugin:3.1.1:jxr org.apache.maven.plugins:maven-pmd-plugin:3.14.0:pmd"
                            }
                        }
                        post {
                            always {
                                recordIssues(enabledForFailure: true, tool: pmdParser(pattern: 'target/pmd.xml'))
                            }
                        }
                    } // end pmd
                  
                    stage('Vulnerabilities') {
                        steps {
                            container('jhipster') {
                                sh "./mvnw -B -e -T 1C org.owasp:dependency-check-maven:6.5.3:aggregate -Dformat=xml" // -DfailBuildOnCVSS=10"
                            }
                        }
                        post {
                            always {
                                dependencyCheckPublisher(failedTotalCritical : 100, unstableTotalCritical : 100)
                            }
                        }
                    } // end vulnerabilities
                } // end parallel
            } // end security checks
			
			stage('Push Docker') {
				steps {
					container('maven') {
						script {
							APP_NAME=pipelineParams.app_name
							BRANCH_NAME="-"+BRANCH
   
							if (BRANCH_NAME == '-main' || BRANCH_NAME == '-master') {
								APP_BRANCH = APP_NAME
							}  else {
								APP_BRANCH = APP_NAME+BRANCH_NAME
							}
							
							// jh-demo-gateway   -Djib.container.ports=8080
							sh "mvn -pl gateway -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/jh-demo-gateway-dev:${POM_VERSION}-${BUILD_NUM} -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.allowInsecureRegistries=true"
							sh "mvn -pl gateway -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/jh-demo-gateway-dev:latest -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.allowInsecureRegistries=true"
							
							// jh-demo-carapp    -Djib.container.ports=8081
							sh "mvn -pl carapp -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/jh-demo-carapp-dev:${POM_VERSION}-${BUILD_NUM} -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.allowInsecureRegistries=true"
							sh "mvn -pl carapp -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/jh-demo-carapp-dev:latest -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.allowInsecureRegistries=true"
							
							// jh-demo-customerapp  -Djib.container.ports=8082
							sh "mvn -pl customerapp -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/jh-demo-customerapp-dev:${POM_VERSION}-${BUILD_NUM} -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.allowInsecureRegistries=true"
							sh "mvn -pl customerapp -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/jh-demo-customerapp-dev:latest -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.allowInsecureRegistries=true"
						}
					}
				}
			} // push docker
			
			stage('argoCD') {
				steps {
					container('git') {

						script {
							argoRepoUrl = "https://clearavenue:${GIT_CREDS_PSW}@github.com/clearavenue/argocd-dev-apps.git"

							APP_NAME=pipelineParams.app_name
							BRANCH_NAME="-"+BRANCH

							if (BRANCH_NAME == '-main' || BRANCH_NAME == '-master') {
								APP_BRANCH = APP_NAME
							}  else {
								APP_BRANCH = APP_NAME+BRANCH_NAME
							}

							sh """
                                git clone $argoRepoUrl argocd
                                cd argocd
                                cp templates/template-application.yaml apps/jh-demo-gateway-application.yaml
                                sed -i \"s|APP_BRANCH|jh-demo-gateway|g\" apps/jh-demo-gateway-application.yaml
                                cat apps/jh-demo-gateway-application.yaml
                            
                                cd apps
                                mkdir -p jh-demo-gateway
                                cd jh-demo-gateway
                                cp ../../templates/app/jhipster-webapp-deployment.yaml deployment.yaml
                                cp ../../templates/app/service.yaml .
                                cp ../../templates/app/serviceaccount.yaml .
                                cp ../../templates/app/namespace.yaml .
                                cp ../../templates/app/virtualservice.yaml .

                                sed -i \"s|APP_BRANCH|jh-demo-gateway|g\" deployment.yaml
                                sed -i \"s|DOCKERUSER|$DOCKER_CREDS_USR|g\" deployment.yaml
                                sed -i \"s|VERSION|$POM_VERSION-$BUILD_NUM|g\" deployment.yaml
                                sed -i \"s|DB_NAME|jh-demo-gateway|g\" deployment.yaml
                                sed -i \"s|DB_USER|jh-demo-gateway|g\" deployment.yaml
                                sed -i \"s|DB_PWD|jh-demo-gateway|g\" deployment.yaml
                                sed -i \"s|APP_BRANCH|jh-demo-gateway|g\" service.yaml
                                sed -i \"s|APP_BRANCH|jh-demo-gateway|g\" serviceaccount.yaml
                                sed -i \"s|APP_BRANCH|jh-demo-gateway|g\" namespace.yaml
                                sed -i \"s|APP_BRANCH|jh-demo-gateway|g\" virtualservice.yaml

                                cat namespace.yaml
                                cat deployment.yaml
                                cat service.yaml
                                cat serviceaccount.yaml
                                cat virtualservice.yaml

                               cd ../..
                               cp templates/template-application.yaml apps/carapp-application.yaml
                               sed -i \"s|APP_BRANCH|carapp|g\" apps/carapp-application.yaml
                               cat apps/carapp-application.yaml
                            
                               cd apps
                               mkdir -p carapp
                               cd carapp
                               cp ../../templates/app/jhipster-ms-postgres-deployment.yaml deployment.yaml
                               cp ../../templates/app/jhipster-ms-service.yaml service.yaml
                               cp ../../templates/app/serviceaccount.yaml .
                               cp ../../templates/app/namespace.yaml .
                               cp ../../templates/app/virtualservice-ms.yaml virtualservice.yaml

                               sed -i \"s|APP_BRANCH|carapp|g\" deployment.yaml
                               sed -i \"s|DOCKERUSER|$DOCKER_CREDS_USR|g\" deployment.yaml
                               sed -i \"s|VERSION|$POM_VERSION-$BUILD_NUM|g\" deployment.yaml
                               sed -i \"s|SERVICE_PORT|8081|g\" deployment.yaml
                               sed -i \"s|DB_NAME|carapp|g\" deployment.yaml
                               sed -i \"s|DB_USER|carapp|g\" deployment.yaml
                               sed -i \"s|DB_PWD|carapp|g\" deployment.yaml
                               sed -i \"s|APP_BRANCH|carapp|g\" service.yaml
                               sed -i \"s|SERVICE_PORT|8081|g\" service.yaml
                               sed -i \"s|APP_BRANCH|carapp|g\" serviceaccount.yaml
                               sed -i \"s|APP_BRANCH|carapp|g\" namespace.yaml
                               sed -i \"s|APP_BRANCH|carapp|g\" virtualservice.yaml

                               cat namespace.yaml
                               cat deployment.yaml
                               cat service.yaml
                               cat serviceaccount.yaml
                               cat virtualservice.yaml

                               cd ../..
                               cp templates/template-application.yaml apps/customerapp-application.yaml
                               sed -i \"s|APP_BRANCH|customerapp|g\" apps/customerapp-application.yaml
                               cat apps/customerapp-application.yaml
                            
                               cd apps
                               mkdir -p customerapp
                               cd customerapp
                               cp ../../templates/app/jhipster-ms-postgres-deployment.yaml deployment.yaml
                               cp ../../templates/app/jhipster-ms-service.yaml service.yaml
                               cp ../../templates/app/serviceaccount.yaml .
                               cp ../../templates/app/namespace.yaml .
                               cp ../../templates/app/virtualservice-ms.yaml virtualservice.yaml

                               sed -i \"s|APP_BRANCH|customerapp|g\" deployment.yaml
                               sed -i \"s|DOCKERUSER|$DOCKER_CREDS_USR|g\" deployment.yaml
                               sed -i \"s|VERSION|$POM_VERSION-$BUILD_NUM|g\" deployment.yaml
                               sed -i \"s|SERVICE_PORT|8082|g\" deployment.yaml
                               sed -i \"s|DB_NAME|customerapp|g\" deployment.yaml
                               sed -i \"s|DB_USER|customerapp|g\" deployment.yaml
                               sed -i \"s|DB_PWD|customerapp|g\" deployment.yaml
                               sed -i \"s|APP_BRANCH|customerapp|g\" service.yaml
                               sed -i \"s|SERVICE_PORT|8082|g\" service.yaml
                               sed -i \"s|APP_BRANCH|customerapp|g\" serviceaccount.yaml
                               sed -i \"s|APP_BRANCH|customerapp|g\" namespace.yaml
                               sed -i \"s|APP_BRANCH|customerapp|g\" virtualservice.yaml

                               cat namespace.yaml
                               cat deployment.yaml
                               cat service.yaml
                               cat serviceaccount.yaml
                               cat virtualservice.yaml

                               cd ../..
                        
                               git config --global user.email bill.hunt@clearavenue.com
                               git config --global user.name clearavenue
                               git add .
                               git commit -am \"added $APP_BRANCH:$POM_VERSION-$BUILD_NUM to argoCD for deployment"
                               git push
                            """
						}
					}
				}
			}  // argocd

        } // end stages

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
