# Practo on AWS: Week 1 Summary

**Case:** Practo, an Indian healthcare platform that connects patients with doctors, clinics and hospitals, running on AWS.

## Sources found
| # | Source | Type | What it gives us |
|---|---|---|---|
| 1 | AWS "This Is My Architecture" episode with Practo (YouTube, [https://www.youtube.com/watch?v=YgABcxC4pwQ]) | Primary (Practo engineer speaking) | Architecture, scaling approach, cost saving |
| 2 | Practo's Worker Pod Autoscaler repo (GitHub) | Primary | Queue-driven autoscaling on AWS SQS |
| 3 | Practo's GitHub org, Tipoca Stream | Primary | RDS to Redshift data pipeline |
| 4 | Mydbops case study on Practo's RDS tuning | Vendor-authored | Database problems, fixes, claimed 5x gain |
| 5 | practo.com/providers | Company page | Product scale claims (undated) |
| 6 | Bloomberg, May 2016 | Press | Scale snapshot |
| 7 | CXOToday, DQ India founder interview, TechCrunch 2015 | Press / interview | AWS citing Practo, appointment volume, business model |

## Background
Practo started in India as an appointment-booking service and grew into a two-sided platform: patient search plus practice-management software for doctors (Practo Ray). In 2016 Bloomberg reported 60 million users, 200,000 doctors, 10,000 hospitals and over 40 million appointments a year. Those are 2016 numbers, not current ones.

## Problem
Practo began as a monolith on AWS Auto Scaling. As it grew it split into services, but developers kept waiting on the infrastructure team. On the database side, slow auto-generated SQL, an old MySQL 5.1 engine and disk bottlenecks slowed searches and bookings at peak hours, and RDS costs were rising.

## Solution and architecture
- **Request path:** user, then Auto Scaling and a load balancer, then NGINX router machines, then a second load balancer in front of a Kubernetes cluster (managed with kops) running about 80 services.
- **Developer workflow:** each service has a Dockerfile; CI/CD builds an image on every push; a small in-house tool lets developers declare the extra infrastructure they need (RDS, Memcached, Redis) without waiting on the infra team.
- **Background jobs:** services drop jobs into SQS; worker pods run on cheaper spot instances and are scaled by Practo's own autoscaler based on queue metrics.
- **Monitoring:** load balancer logs to S3, request logs and alerts in Graylog, errors in Sentry.
- **Database and data:** RDS MySQL tuned with provisioned IOPS, upgrades to 5.6, rewritten queries and cleaner indexes; a separate pipeline streams RDS data into Redshift.
- **Next steps (per the speaker):** a service mesh and Amazon EKS.

## Cost and outcomes
- Moving to one shared Kubernetes cluster saved **over 40%** of cost, because services no longer each needed their own Auto Scaling group (speaker's claim, no baseline given).
- Database tuning gave a **5x performance gain** and lower RDS bills (vendor's claim, no amounts given).
- Business scale: see the 2016 figures above.
- **Not found:** absolute cloud spend, uptime or latency figures, and an official AWS case study page.

## Caveats
- Sources disagree on the founding year (2008 or 2009) and, in 2015, on hospital count (8,000 versus 10,000).
- The 40% and 5x figures are self-reported.
- The video transcript is machine-generated; names were checked by hand.

## Link to our exercise
We will build a Practo-style doctor search and booking API on Lambda, API Gateway and DynamoDB, then compare its cold start, scaling and cost per 1,000 requests against the always-on cluster approach Practo describes. This is our own design, not Practo's.
