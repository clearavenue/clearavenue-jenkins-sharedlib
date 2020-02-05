Sample Jenkinsfile

```
@Library('clearavenue/clearavenue-jenkins-sharedlib') _
  java-maven-devsecops-pipeline {
  project_name = fdadi-frontend
  docker_user = clearavenuedocker
  deployment_yaml = fdadi-frontend-deployment.yaml
  service_url = http://a4dbbc119bd2e4124bd4bcab0ee5528e-170228795.us-east-1.elb.amazonaws.com
}
```

Note:  Don't forget the '_' at the end of the @Library line !!!
