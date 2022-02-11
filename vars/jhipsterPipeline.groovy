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
    image: maven:3.6.1-jdk-11-slim
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
                    container('maven') {
                        script {
                             BUILD_PROFILE=pipelineParams.buildProfile
                             sh "mvn -B -e -T 1C clean package ${BUILD_PROFILE} -DskipTests"
                        }
                    }
                }
            }

//            stage('JUnit') {
//                steps {
//                    container('maven') {
//                        sh "mvn -B -e -T 1C test"
//                        junit 'target/surefire-reports/**/*.xml'
//                    }
//                }
//            }

//            stage('SecurityChecks') {
//                parallel {
//                    stage('Checkstyle code') {
//                        steps {
//                            container('maven') {
//                                sh "mvn -B -e -T 1C org.apache.maven.plugins:maven-checkstyle-plugin:3.1.2:checkstyle -Dcheckstyle.config.location=google_checks.xml"
//                            }
//                        }
//                        post {
//                            always {
//                                recordIssues(enabledForFailure: false, tool: checkStyle(pattern: 'target/checkstyle-result.xml'))
//                            }
//                        }
//                    }

//                    stage('CodeCoverage') {
//                        steps {
//                            container('maven') {
//                                sh "mvn -B -e -T 1C org.jacoco:jacoco-maven-plugin:0.8.7:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.7:report"
//                                jacoco(execPattern: 'target/jacoco.exec', classPattern: 'target/classes', sourcePattern: 'src/main/java', exclusionPattern: 'src/test*', changeBuildStatus: true,
//                                       minimumInstructionCoverage : '30', maximumInstructionCoverage : '31',
//                                       minimumBranchCoverage : '30', maximumBranchCoverage : '31',
//                                       minimumComplexityCoverage : '30', maximumComplexityCoverage : '31',
//                                       minimumLineCoverage : '30', maximumLineCoverage : '31',
//                                       minimumMethodCoverage : '30', maximumMethodCoverage : '31',
//                                       minimumClassCoverage : '30', maximumClassCoverage : '31')
//                            }
//                        }
//                    }

//                    stage('SpotBugs') {
//                        steps {
//                           container('maven') {
//                                sh "mvn -B -e -T 1C com.github.spotbugs:spotbugs-maven-plugin:4.5.0.0:check -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.failOnError=false"
//                            }
//                        }
//                        post {
//                            always {
//                                recordIssues(enabledForFailure: true, tool: spotBugs())
//                            }
//                        }
//                    }

//                    stage('PMD') {
//                        steps {
//                            container('maven') {
//                                sh "mvn -B -e org.apache.maven.plugins:maven-jxr-plugin:3.1.1:jxr org.apache.maven.plugins:maven-pmd-plugin:3.14.0:pmd"
//                            }
//                        }
//                        post {
//                            always {
//                                recordIssues(enabledForFailure: true, tool: pmdParser(pattern: 'target/pmd.xml'))
//                            }
//                        }
//                    }
//                }
//            }

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
                            
                            sh "mvn -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/${APP_BRANCH}:${POM_VERSION}-${BUILD_NUM} -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.container.ports=8080 -Djib.allowInsecureRegistries=true"
                            
                            sh "mvn -B -e -T 1C package com.google.cloud.tools:jib-maven-plugin:3.2.0:build -Dimage=${DOCKER_CREDS_USR}/${APP_BRANCH}:latest -DskipTests -Djib.to.auth.username=${DOCKER_CREDS_USR} -Djib.to.auth.password=${DOCKER_CREDS_PSW} -Djib.container.ports=8080 -Djib.allowInsecureRegistries=true"
                        }
                    }
                }
            }

            stage('argoCD') {
                steps {
                    container('git') {

                    script {
                         argoRepoUrl = "https://clearavenue:${GIT_CREDS_PSW}@github.com/clearavenue/argocd-apps.git"

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
                             cp templates/template-application.yaml apps/$APP_BRANCH-application.yaml
                             sed -i \"s|APP_BRANCH|$APP_BRANCH|g\" apps/$APP_BRANCH-application.yaml
                             cat apps/$APP_BRANCH-application.yaml
                             
                             cd apps
                             mkdir -p $APP_BRANCH
                             cd $APP_BRANCH
                             cp -R ../../templates/app/* .

                             cat > deployment.yaml << EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "8080"
    prometheus.io/path: "/actuator/prometheus"
  labels:
    app: APP_BRANCH
  name: APP_BRANCH
  namespace: APP_BRANCH
spec:
  progressDeadlineSeconds: 600
  replicas: 1
  revisionHistoryLimit: 10
  selector:
    matchLabels:
      app: APP_BRANCH
  strategy:
    rollingUpdate:
      maxSurge: 25%
      maxUnavailable: 25%
    type: RollingUpdate
  template:
    metadata:
      labels:
        app: APP_BRANCH
    spec:
      serviceAccountName: APP_BRANCH-service-account
      containers:
      - image: DOCKERUSER/APP_BRANCH:VERSION
        env:
        - name: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
          value: http://admin:admin@jhipster-registry.jhipster-registry.svc.cluster.local:8761/eureka
        - name: JHIPSTER_SLEEP
          value: "30"
        - name: MANAGEMENT_METRICS_EXPORT_PROMETHEUS_ENABLED
          value: "true"
        - name: SPRING_CLOUD_CONFIG_URI
          value: http://admin:admin@jhipster-registry.jhipster-registry.svc.cluster.local:8761/config
        - name: SPRING_DATASOURCE_URL
          value: jdbc:postgresql://postgresql.postgresql.svc.cluster.local:5432/app
        - name: SPRING_DATASOURCE_USERNAME
          value: app
        - name: SPRING_DATASOURCE_PASSWORD
          value: app
        - name: SPRING_DATA_JEST_URI
          value: http://elasticsearch.elasticsearch.svc.cluster.local:9200
        - name: SPRING_ELASTICSEARCH_REST_URIS
          value: http://elasticsearch.elasticsearch.svc.cluster.local:9200
        - name: SPRING_PROFILES_ACTIVE
          value: dev,swagger
        - name: SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI
          value: https://keycloak.devsecops.clearavenue.com/auth/realms/jhipster
        - name: SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID
          value: web_app
        - name: SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET
          value: web_app
        imagePullPolicy: Always
        name: APP_BRANCH
        ports:
        - containerPort: 8080
          name: web
          protocol: TCP
        terminationMessagePath: /dev/termination-log
        terminationMessagePolicy: File
        readinessProbe:
          httpGet:
            path: /
            port: 8080
          initialDelaySeconds: 20
          failureThreshold: 300
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /
            port: 8080
          initialDelaySeconds: 300
          periodSeconds: 5
          failureThreshold: 3
        volumeMounts:
        - mountPath: /tmp
          name: temp-dir
      dnsPolicy: ClusterFirst
      restartPolicy: Always
      schedulerName: default-scheduler
      volumes:
      - emptyDir: {}
        name: temp-dir
      terminationGracePeriodSeconds: 30
                             EOF

                             sed -i \"s|APP_BRANCH|$APP_BRANCH|g\" deployment.yaml
                             sed -i \"s|DOCKERUSER|$DOCKER_CREDS_USR|g\" deployment.yaml
                             sed -i \"s|VERSION|$POM_VERSION-$BUILD_NUM|g\" deployment.yaml
                             sed -i \"s|APP_BRANCH|$APP_BRANCH|g\" service.yaml
                             sed -i \"s|APP_BRANCH|$APP_BRANCH|g\" serviceaccount.yaml
                             sed -i \"s|APP_BRANCH|$APP_BRANCH|g\" namespace.yaml
                             sed -i \"s|APP_BRANCH|$APP_BRANCH|g\" virtualservice.yaml

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
