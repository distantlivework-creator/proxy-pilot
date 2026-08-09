from app.cluster import choose_dns_addresses


def test_dns_removes_node_after_failure_threshold():
    nodes = [
        {"address": "1.1.1.1", "healthy": 1, "consecutive_failures": 0},
        {"address": "2.2.2.2", "healthy": 0, "consecutive_failures": 2},
    ]
    decision = choose_dns_addresses(nodes, 2, {"1.1.1.1", "2.2.2.2"})
    assert decision.desired_addresses == ("1.1.1.1",)
    assert decision.changed


def test_dns_keeps_node_during_single_transient_failure():
    nodes = [{"address": "1.1.1.1", "healthy": 0, "consecutive_failures": 1}]
    decision = choose_dns_addresses(nodes, 2, {"1.1.1.1"})
    assert decision.desired_addresses == ("1.1.1.1",)
    assert not decision.changed


def test_dns_is_not_emptied_when_all_nodes_fail():
    nodes = [{"address": "1.1.1.1", "healthy": 0, "consecutive_failures": 3}]
    decision = choose_dns_addresses(nodes, 2, {"1.1.1.1"})
    assert decision.desired_addresses == ("1.1.1.1",)
    assert decision.reason == "all_nodes_uncertain"
