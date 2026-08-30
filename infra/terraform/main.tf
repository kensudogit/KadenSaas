##############################################################################
# 架電SaaS の AWS 構成。
#
# ★ サービスを 5 つに分ける。同居させない理由がそれぞれ違う。
#
#     api         Spring Boot。業務 API。スキーマの所有者（Flyway）
#     voice-web   FastAPI。Twilio の webhook と内部 API
#     voice-media FastAPI。Media Streams の WebSocket
#                 ★ voice-web と必ず分ける。1 通話あたり毎秒 50 メッセージで、
#                   同居させると同時通話が増えるほど webhook の応答が遅れ、
#                   Twilio が再送を始める。負荷が高いときに、いちばん
#                   壊れてほしくない経路が最初に壊れる。
#                   スケールの軸も違う（webhook は同時ユーザー数、
#                   media は同時通話数）
#     voice-jobs  録音の取得・保存期限切れの削除・AI 分析
#                 ★ 公開しない。ALB に繋がない
#     web         Next.js
#
# ★ 録音は個人情報。S3 は公開せず、期限付き署名 URL でしか読ませない。
#   バケットのブロックパブリックアクセスを全部有効にしてある。
#
# ★ Twilio の Auth Token は「他人名義で電話をかけられる鍵」であり、
#   同時に Webhook の署名検証鍵でもある。Secrets Manager に置き、
#   タスク定義には ARN の参照だけを書く。環境変数に平文で入れない。
##############################################################################

terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "kaden-saas"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "kaden-${var.environment}"

  # ★ 2 AZ に分ける。RDS のマルチ AZ と ECS の分散の前提。
  #   架電中に片方の AZ が落ちても通話が全部切れる、を避ける
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  # 全サービス共通の環境変数。秘密情報はここに書かない（secrets で渡す）
  common_env = {
    APP_ENV = "production"
  }
}
