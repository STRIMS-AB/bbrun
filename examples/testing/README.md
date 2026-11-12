# Testing a backend API with bbrun-cli

Start with **[order-api](order-api/README.md)**: one backend, one configured
resource, several app-like scenarios and negative cases.

The tester acts as an API client. Scripts send requests, retain returned IDs
for subsequent requests, assert status and behavior, and validate the public
response contract with DRef. They do not implement the backend or reshape its
responses through mapping files.

The backend implementation example lives separately under
[`../integration/order-service`](../integration/order-service/README.md).
