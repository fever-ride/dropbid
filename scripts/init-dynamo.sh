#!/bin/bash
# Creates DynamoDB tables for auction-service and bid-service.
# Runs once at startup via the init-dynamo container.

set -e
ENDPOINT="http://dynamodb-local:8000"

echo "Creating Auctions table..."
aws dynamodb create-table \
  --endpoint-url "$ENDPOINT" \
  --table-name Auctions \
  --attribute-definitions \
    AttributeName=auctionId,AttributeType=S \
    AttributeName=status,AttributeType=S \
    AttributeName=sellerId,AttributeType=S \
  --key-schema AttributeName=auctionId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --global-secondary-indexes \
    '[
      {
        "IndexName": "status-index",
        "KeySchema": [{"AttributeName":"status","KeyType":"HASH"}],
        "Projection": {"ProjectionType":"ALL"}
      },
      {
        "IndexName": "seller-index",
        "KeySchema": [{"AttributeName":"sellerId","KeyType":"HASH"}],
        "Projection": {"ProjectionType":"ALL"}
      }
    ]' || echo "Auctions table already exists"

echo "Creating Bids table..."
aws dynamodb create-table \
  --endpoint-url "$ENDPOINT" \
  --table-name Bids \
  --attribute-definitions \
    AttributeName=bidId,AttributeType=S \
    AttributeName=auctionId,AttributeType=S \
    AttributeName=bidderId,AttributeType=S \
    AttributeName=createdAt,AttributeType=S \
  --key-schema AttributeName=bidId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --global-secondary-indexes \
    '[
      {
        "IndexName": "auction-index",
        "KeySchema": [
          {"AttributeName":"auctionId","KeyType":"HASH"},
          {"AttributeName":"createdAt","KeyType":"RANGE"}
        ],
        "Projection": {"ProjectionType":"ALL"}
      },
      {
        "IndexName": "bidder-index",
        "KeySchema": [
          {"AttributeName":"bidderId","KeyType":"HASH"},
          {"AttributeName":"createdAt","KeyType":"RANGE"}
        ],
        "Projection": {"ProjectionType":"ALL"}
      }
    ]' || echo "Bids table already exists"

echo "DynamoDB tables ready."
