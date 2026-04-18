// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";
import "@openzeppelin/contracts/utils/Nonces.sol";

contract MineFiVault is EIP712, Nonces {
    using ECDSA for bytes32;

    bytes32 private constant WITHDRAW_TYPEHASH =
        keccak256("Withdraw(address player,uint256 amount,uint256 nonce)");

    mapping(address => uint256) public balances;
    address public server;

    bytes32 public balanceRoot;
    uint256 public lastRootUpdate;

    event Deposited(address indexed player, uint256 amount);
    event Withdrawn(address indexed player, uint256 amount, uint256 gasCost);
    event BalanceRootUpdated(bytes32 root, uint256 timestamp);
    event DisputeWithdrawal(address indexed player, uint256 amount);

    constructor() EIP712("MineFiVault", "1") {
        server = msg.sender;
    }

    function deposit(address player) external payable {
        require(msg.value > 0, "zero deposit");
        balances[player] += msg.value;
        emit Deposited(player, msg.value);
    }

    function withdraw(
        address player,
        uint256 amount,
        bytes calldata signature
    ) external {
        require(msg.sender == server, "only server");
        require(balances[player] >= amount, "insufficient balance");

        uint256 gasStart = gasleft();

        uint256 nonce = _useNonce(player);
        bytes32 structHash = keccak256(
            abi.encode(WITHDRAW_TYPEHASH, player, amount, nonce)
        );
        address signer = _hashTypedDataV4(structHash).recover(signature);
        require(signer == player, "invalid signature");

        uint256 gasUsed = gasStart - gasleft() + 50000;
        uint256 gasCost = gasUsed * tx.gasprice;
        require(amount > gasCost, "amount too small for gas");

        balances[player] -= amount;
        uint256 payout = amount - gasCost;

        (bool sent, ) = payable(player).call{value: payout}("");
        require(sent, "transfer to player failed");

        (bool reimbursed, ) = payable(server).call{value: gasCost}("");
        require(reimbursed, "server reimbursement failed");

        emit Withdrawn(player, payout, gasCost);
    }

    function updateBalanceRoot(bytes32 _root) external {
        require(msg.sender == server, "only server");
        balanceRoot = _root;
        lastRootUpdate = block.timestamp;
        emit BalanceRootUpdated(_root, block.timestamp);
    }

    function verifyBalance(
        address player,
        uint256 claimedBalance,
        bytes32[] calldata proof
    ) external view returns (bool) {
        bytes32 leaf = keccak256(bytes.concat(keccak256(abi.encode(player, claimedBalance))));
        return MerkleProof.verify(proof, balanceRoot, leaf);
    }

    function emergencyWithdraw(
        uint256 claimedBalance,
        bytes32[] calldata proof
    ) external {
        require(balanceRoot != bytes32(0), "no root published");
        require(block.timestamp > lastRootUpdate + 1 hours, "dispute period not elapsed");

        bytes32 leaf = keccak256(bytes.concat(keccak256(abi.encode(msg.sender, claimedBalance))));
        require(MerkleProof.verify(proof, balanceRoot, leaf), "invalid proof");

        uint256 amount = claimedBalance;
        if (amount > balances[msg.sender]) {
            amount = balances[msg.sender];
        }
        require(amount > 0, "nothing to withdraw");

        balances[msg.sender] -= amount;
        (bool sent, ) = payable(msg.sender).call{value: amount}("");
        require(sent, "transfer failed");

        emit DisputeWithdrawal(msg.sender, amount);
    }

    function balanceOf(address player) external view returns (uint256) {
        return balances[player];
    }

    function getNonce(address player) external view returns (uint256) {
        return nonces(player);
    }
}
