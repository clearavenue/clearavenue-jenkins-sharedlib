Sample Jenkinsfile

```
@Library('clearavenue/clearavenue-jenkins-sharedlib') _
  java-maven-devsecops-pipeline {
  project_name = fdadi-frontend
  docker_user = clearavenuedocker
  deployment_yaml = fdadi-frontend-deployment.yaml
}
```
