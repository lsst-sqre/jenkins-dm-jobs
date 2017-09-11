def notify = null

node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

try {
  notify.started()

  def gitTag = null
  def eupsTag = null
  def product = 'lsst_distrib qserv_distrib'
  def retries = 3
  def bx = null
  def rebuildId = null
  def buildJob = 'release/run-rebuild'
  def publishJob = 'release/run-publish'
  def year = null
  def week = null

  stage('format weekly tag') {
    if (!params.YEAR) {
      error 'YEAR parameter is required'
    }
    if (!params.WEEK) {
      error 'WEEK parameter is required'
    }

    year = params.YEAR.padLeft(4, "0")
    week = params.WEEK.padLeft(2, "0")

    gitTag = "w.${year}.${week}"
    echo "generated [git] tag: ${gitTag}"

    // eups doesn't like dots in tags, convert to underscores
    eupsTag = gitTag.tr('.', '_')
    echo "generated [eups] tag: ${eupsTag}"
  }

  stage('build') {
    retry(retries) {
      def result = build job: buildJob,
        parameters: [
          string(name: 'PRODUCT', value: product),
          booleanParam(name: 'SKIP_DEMO', value: false),
          booleanParam(name: 'SKIP_DOCS', value: true),
        ],
        wait: true
      rebuildId = result.id
    }
  }

  stage('parse bNNNN') {
    node {
      manifest_artifact = 'lsstsw/build/manifest.txt'

      step([$class: 'CopyArtifact',
            projectName: buildJob,
            filter: manifest_artifact,
            selector: [$class: 'SpecificBuildSelector', buildNumber: rebuildId]
            ])

      def manifest = readFile manifest_artifact
      bx = util.bxxxx(manifest)

      echo "parsed bxxxx: ${bx}"
    }
  }

  stage('eups publish') {
    def pub = [:]

    pub[eupsTag] = {
      retry(retries) {
        util.tagProduct(bx, eupsTag, product, publishJob)
      }
    }
    pub['w_latest'] = {
      retry(retries) {
        util.tagProduct(bx, 'w_latest', 'lsst_distrib', publishJob)
      }
    }
    pub['qserv_latest'] = {
      retry(retries) {
        util.tagProduct(bx, 'qserv_latest', 'qserv_distrib', publishJob)
      }
    }
    pub['qserv-dev'] = {
      retry(retries) {
        util.tagProduct(bx, 'qserv-dev', 'qserv_distrib', publishJob)
      }
    }

    parallel pub
  }

  stage('wait for s3 sync') {
    sleep time: 15, unit: 'MINUTES'
  }

  stage('git tag') {
    retry(retries) {
      // needs eups distrib tag to be sync'd from s3 -> k8s volume
      build job: 'release/tag-git-repos',
        parameters: [
          string(name: 'BUILD_ID', value: bx),
          string(name: 'GIT_TAG', value: gitTag),
          booleanParam(name: 'DRY_RUN', value: false)
        ]
    }
  }

  stage('build eups tarballs') {
    def artifact = [:]
    // disabled
    // see: https://jira.lsstcorp.org/browse/DM-11586
    /*
    artifact['run qserv/docker/build'] = {
      catchError {
        retry(retries) {
          build job: 'qserv/docker/build'
        }
      }
    }
    */

    for (pyver in ['2', '3']) {
      artifact["run release/tarball py${pyver}"] = {
        retry(retries) {
          build job: 'release/tarball',
            parameters: [
              string(name: 'PRODUCT', value: 'lsst_distrib'),
              string(name: 'EUPS_TAG', value: eupsTag),
              booleanParam(name: 'SMOKE', value: true),
              booleanParam(name: 'RUN_DEMO', value: true),
              booleanParam(name: 'PUBLISH', value: true),
              string(name: 'PYVER', value: pyver)
            ]
        }
      }
    }

    parallel artifact
  }

  stage('wait for s3 sync') {
    sleep time: 15, unit: 'MINUTES'
  }

  stage('build stack image') {
    retry(retries) {
      build job: 'release/docker/build-stacktest',
        parameters: [
          string(name: 'TAG', value: eupsTag)
        ]
    }
  }

  stage('build jupyterlabdemo image') {
    retry(retries) {
      // based on lsstsqre/stack image
      build job: 'sqre/infrastructure/build-jupyterlabdemo',
        parameters: [
          string(name: 'BTYPE', value: 'w'),
          stringParam(name: 'YEAR', value: year),
          stringParam(name: 'WEEK', value: week),
          string(name: 'PYVER', value: '3')
        ]
    }
  }

  stage('archive') {
    node {
      results = [
        bnnnn: bx,
        git_tag: gitTag,
        eups_tag: eupsTag
      ]
      util.dumpJson('results.json', results)

      archiveArtifacts([
        artifacts: 'results.json',
        fingerprint: true
      ])
    }
  }
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}
