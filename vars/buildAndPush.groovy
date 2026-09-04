import com.course.PipelineConfig

/**
 * buildAndPush — build image lokal & load ke KinD cluster.
 *
 * Flow (Local Track):
 * 1. docker build — build image di host Docker (via shared socket)
 * 2. kind load   — load image ke KinD cluster containerd
 *
 * Tidak perlu push ke Docker Hub karena KinD bisa pakai image lokal
 * dengan imagePullPolicy: IfNotPresent.
 *
 * Tag = Jenkins BUILD_NUMBER (sequential, traceable)
 */
def call(PipelineConfig cfg, String buildNumber) {
    def image = "${cfg.imageName()}:${buildNumber}"

    if (cfg.buildTool == 'kaniko') {
        echo "Building dengan Kaniko: ${image}"
        sh """
            docker run --rm \
              -v \$(pwd):/workspace \
              -v \$HOME/.docker:/kaniko/.docker:ro \
              gcr.io/kaniko-project/executor:latest \
              --context=dir:///workspace \
              --dockerfile=/workspace/Dockerfile \
              --destination=${image} \
              --no-push \
              --tar-path=/tmp/image.tar
        """
    } else {
        // Docker mode — build di host Docker via shared socket
        echo "Building dengan Docker (BuildKit enabled): ${image}"

        def gitSha = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        def gitMsg = sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim().replace('"', '\\"')

        sh """
            DOCKER_BUILDKIT=1 docker build \
              --pull \
              --build-arg BUILD_NUMBER=${buildNumber} \
              --build-arg COMMIT_SHA=${gitSha} \
              --build-arg "COMMIT_MESSAGE=${gitMsg}" \
              -t ${image} .
        """

        if (cfg.enableSecurityScan) {
            trivyScan(type: 'image', target: image, failOnVuln: true)
        }
    }

    // Load image ke KinD cluster (shared Docker socket = kind bisa akses)
    def kindCluster = cfg.kindClusterName ?: 'devops-local-cluster'
    sh "kind load docker-image ${image} --name ${kindCluster}"

    echo "Image loaded to KinD: ${image}"
    return image
}
