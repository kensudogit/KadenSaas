output "alb_dns_name" {
  description = "ALB の DNS 名。ここに domain_name の CNAME / ALIAS を向ける"
  value       = aws_lb.main.dns_name
}

output "public_base_url" {
  description = <<-EOT
    公開 URL。
    ★ この値を Twilio Console の Webhook URL に登録し、
      voice サービスの PUBLIC_BASE_URL と完全に一致させること。
      1 文字でも違うと署名が一致せず、Webhook が全件 403 になる。
  EOT
  value = "https://${var.domain_name}"
}

output "db_endpoint" {
  value     = aws_db_instance.main.endpoint
  sensitive = true
}

output "db_master_secret_arn" {
  description = "RDS が生成したマスターパスワードの Secret ARN"
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "app_secret_arn" {
  description = <<-EOT
    アプリの秘密情報を入れる Secret。
    ★ Terraform では値を設定していない。tfstate に平文で残さないため、
      作成後に CLI かコンソールで JSON を投入すること:

        aws secretsmanager put-secret-value           --secret-id <この ARN>           --secret-string '{"JWT_SECRET":"...","TWILIO_ACCOUNT_SID":"AC...",...}'
  EOT
  value = aws_secretsmanager_secret.app.arn
}

output "recordings_bucket" {
  value = aws_s3_bucket.recordings.id
}
